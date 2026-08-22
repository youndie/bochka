# bochka

[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![jvm](https://img.shields.io/badge/JVM-25-blue?logoColor=white)](https://openjdk.org/projects/jdk/25/)
[![status](https://img.shields.io/badge/status-early-orange)](BACKLOG.md)
[![s3-tests](https://img.shields.io/badge/ceph%2Fs3--tests-490%2F744-yellowgreen)](ci/s3-tests.sh)
[![license](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![release](https://img.shields.io/badge/release-v0.2.0-blue)](https://github.com/youndie/bochka/releases/tag/v0.2.0)

An S3-compatible object store in Kotlin/JVM. One process, one node, one disk: no erasure coding,
no replication, no quorum. On the outside, the protocol `aws s3`, `mc`, `boto3` and every S3
library already speak; on the inside, an index ordered by the raw bytes of the key, a file per
object, and a GET path that is `sendfile` from that file straight to the socket.

The niche is not "a MinIO replacement". It is the slot that on the JVM is currently empty: what
lives there today is a proxy (`s3proxy` translates S3 onto other backends through jclouds) and a
mock (`S3Mock`, itself Kotlin, shipped as a Testcontainer and a JUnit extension). A store that
actually keeps what you give it is missing — and, because it has to be small to be worth writing,
it can also be the thing you start inside a test.

## Status: it stores objects and real clients can use it

`aws-cli`, `boto3`, `mc` and `rclone` can create a bucket, upload a file — whole or in parts —
list it with a delimiter, read a byte range of it and read it back byte for byte, over plaintext
and through an nginx TLS terminator, and what they upload survives the process being killed.

Built so far: the key as a byte string with its own order, SigV4 verified in both forms, all four
body framings including `aws-chunked` with the signature chain, an HTTP/1.1 server on a selector,
the object operations with `Range`, metadata and checksums, listing in both versions of the
operation with `delimiter` and pagination, multipart upload, and storage — an object is a file
under a UUID, the key lives only in the index, the index is a log with a checksum per record, the
write order is the one whose worst outcome is an orphan rather than a key pointing at nothing, and
a `GET` is `transferTo` from that file straight into the socket.

Objects can also be copied server-side, written and read conditionally (`If-Match` and the rest),
listed with their owners, and asked about without their bytes — `GetObjectAttributes`, `partNumber` on a
read, and the checksum of an assembled object, which is the checksum of its parts' checksums.

Since then: bucket and object tags with a CORS configuration and preflight, browser `POST` form
uploads with a policy and both signature versions, versioning end to end — delete markers, reads
and deletes by `versionId`, `ListObjectVersions` in pages — object lock with retention in both
modes and legal hold, access keys narrowed to a mode and a set of buckets, and lifecycle rules
that are **applied** rather than stored: objects and noncurrent versions expire, orphaned delete
markers and abandoned uploads go, and `x-amz-expiration` says when.

Then an access model, in the layer that fits a store whose users are its access keys: an owner
per bucket and per version, canned ACLs stored and **enforced**, and one order between the two
models of permissions — the key scope narrows first, the ACL decides inside what it left, and
neither can hand back what the other took away.

On top of that: bucket policies, which are the first layer here that **grants** rather than takes
away, a bucket logging configuration whose target has to agree in its own policy before it is
accepted, and `PublicAccessBlock` — four switches that refuse a public ACL and a public policy as
they arrive and stop obeying the ones already stored. Every one of the four does something; a flag
written down and not applied is read as a lock that is not there.

And encryption with a key the client brings: `SSE-C` on single and multipart uploads, with the
server keeping the algorithm and the key's MD5 and never the key. An object nobody encrypted is
untouched by it — same `transferTo`, same cost — and what an encrypted one costs is a measured
number rather than a shrug.

Delivered: a distribution, an image on `ghcr.io` and five artifacts published per release.
[BACKLOG.md](BACKLOG.md) says which milestone each thing is, and every closed one ends with what
came out differently than planned.

Anything below describing behaviour bochka does not have says so — that is the project's first
rule, `main` describes what exists.

## Measured, not assumed

The whole read path exists for one property, and identical bytes come out either way — so it is
measured rather than believed. Median of seven runs, spread printed beside every number
([docs/measurements.md](docs/measurements.md)):

> **Reading into the heap costs 7.6–8.0× the processor per byte that `transferTo` does** — across
> a real network card, between two machines. Over loopback the same comparison says 5.3×, and the
> difference is the point: loopback has no device in it, so it understates the thing the read path
> is built for by about half.

Features are measured against that path rather than assumed to be free. Lifecycle rules add **eight
nanoseconds** to a read of a bucket that has none — one failed map lookup — and 555 ns to one that
gets an `x-amz-expiration` header, of which the rule lookup is 62 and the rest is formatting a
date. A sweep over a million versions takes 4.5 seconds against a period of an hour. Neither
number was visible end-to-end across a network, and that is recorded too: a request costing
hundreds of microseconds cannot be asked about nanoseconds.

The measurements that came out against the plan are the more useful half, and there have been
several. The buffer the upload path uses turns out not to matter — size and kind are both inside
the noise, so nothing was changed. `splice(2)` through FFM is not being introduced, because the
most it could remove is a quarter of a core at the rate a single disk sustains. And the reason
this project terminates TLS outside the process turned out to be a different reason than the one
written down for a year — see below.

## How many versions, stated rather than discovered

Every key lives in memory, so the number of index entries is bounded by the heap whether anybody
says so or not. Measured at **650 bytes of index per entry** (586 for a forty-byte key, 647 for a
hundred-byte one — the larger is what the code uses), with half the heap allowed to be index:

| `-Xmx` | `Runtime.maxMemory()` | Versions | A full collection costs |
|---|---|---|---|
| 64 MiB, the development profile | 61.9 MiB | 49 908 | — |
| **512 MiB, what ships** | **494.9 MiB** | **399 215** | **0.93 s** |
| 2 GiB | 1979 MiB | 1 596 860 | 3.84 s |
| 4 GiB | 3959 MiB | 3 193 720 | 7.56 s, and 27 s to open |

The middle column is there because it is the one being divided, and it is **not** `-Xmx`. Under
`-XX:+UseSerialGC` one survivor space is left out of the reported maximum — nothing can be
allocated in both at once — so a 512 MiB heap reports 494.9 MiB. These numbers were `-Xmx / 2 / 650`
for a year, which is about 3.4 % more objects than the process would ever accept; the ceiling it
prints as `object ceiling` on its first line has always been the smaller one.

**The last column is why the table is not an invitation.** The live set is the index, so a full
collection grows with it, and every row above the shipped one describes a configuration this
project has measured rather than one it recommends: 7.56 seconds of stop-the-world at 4 GiB is a
request timeout, not a hiccup. Under the measured load a full collection did not happen at all — it
is the price of the event and not its frequency — but the event does arrive, from a filling old
generation or from somebody taking a heap dump. Above a gibibyte the process says so itself, on the
line after the ceiling.

**And the whole table is about one collector.** `Runtime.maxMemory()` is a property of the
collector, so the ceiling is too: at the same `-Xmx512M` it is 367 404 under ParallelGC, 399 215
under SerialGC and 412 977 under G1. That is why the startup log names the collector — a wrapper
that swaps it moves a number published here without touching a word of it.

**Versions, not objects**, and the distinction is only free in a bucket that does not version. In
one that does, ten writes to a key are ten entries and a delete adds an eleventh — so a bucket with
versioning on holds as many objects as its history allows, not as many as the table says. The
number itself did not move when versioning arrived; what it counts did.

Going over it is a **refusal to start**, not a slide into swap. A process that comes up and then
thrashes looks like a slow disk to everybody who did not write the index; a process that will not
start says what is wrong. A new key is refused the same way once the ceiling is reached — an
overwrite is not, because a full store has to be able to make itself smaller.

That ceiling is also why the index log is compacted. Recovery is proportional to the **log**, not
to what is still live in it: half a million objects written three times each open in 3.5 seconds,
and in 0.76 after a compaction. Unread log is not disk space, it is time the server is not
answering.

What the research produced before any of it was a list of things that are true and counter-intuitive, each verified
against a source rather than remembered. They are the reason the design looks the way it does:

- **"Seven operations" is the client's scope, not the server's.** `aws s3 cp` sends its body as
  `aws-chunked` frames with a signature per chunk by default, deletes `Content-Length`, and puts
  the real length in `X-Amz-Decoded-Content-Length`. A server that reads "Content-Length bytes off
  the socket" does not work with the AWS CLI at all.
- **`Expect: 100-continue` costs exactly one second per upload if you skip it.** botocore waits
  one second, then sends the body anyway — which looks like a slow network, not like a defect.
- **The S3 keyspace does not fit in a filesystem.** Measured on the machine this was written on:
  `Photo.JPG` and `photo.jpg` are one file on APFS, and so are the composed and decomposed spellings
  of `café.txt` — different bytes, different S3 keys, one file, and in both cases the second write
  silently destroyed the first. `NAME_MAX` is 255 against a key of up to 1024 bytes, and the objects
  `a/b` and `a/b/c` cannot coexist. All four are the same bug — a filename derived from the key —
  so the fix is not escaping but never deriving one: on disk an object is a UUID, and the key lives
  only in the index.
- **MinIO keeps no key index at all**; its listing is a directory walk. The premise that an index
  "cannot be rebuilt by scanning" is contradicted by the reference implementation, which scans.
  The conclusion survives; the reason for it does not — and the walk turns out to be evidence
  rather than a model, since MinIO had to build a cache of scan results on top of it, and a cache
  over a traversal is something only an expensive traversal needs.
- **"Lexicographical order" is wrong by default on the JVM.** `String.compareTo` sorts `😀` before
  `！`; UTF-8 byte order is the reverse. Keys are bytes here, compared unsigned, because a
  `TreeMap<String, …>` breaks pagination silently and only on keys nobody puts in a test.
- **`sendfile` only works one way.** Read in the JDK 25 sources: `transferFrom` takes its fast
  path only when the source is a `FileChannelImpl`; a socket falls into a loop through an 8 KiB
  heap buffer. GET can be zero-copy, PUT never can.
- **kTLS is not reachable from the JVM through FFM.** The syscall is; the keys are not — JSSE
  exports no traffic secrets, and what is missing is the secrets, not the call. So TLS terminates
  in front of the process. This used to say "which loses nothing, because nginx does the same
  zero-copy send over TLS itself"; measuring it showed that half wrong. `SSL_sendfile` applies
  when nginx serves a **file**, and in front of a store it proxies a socket — the same TLS with
  the same kTLS costs 0.898 processor-seconds per gibibyte with a file and 2.408 relaying. What
  moving the TLS out actually buys is that this server's read path never sees it: 0.27 s/GiB
  against nginx's 2.4. `BOCHKA_ACCEL_REDIRECT` hands the file over and takes 2.85× of that back,
  at a price named in [deploy/README.md](deploy/README.md). The way inside exists but means
  replacing the TLS stack, not adding a flag.

Two of those come from reading MinIO's source, two from reading the JDK's, and three from running
something on this machine and looking at the output.

## Internals

| | |
|---|---|
| Index | bitcask: an append-only log of index mutations plus an ordered in-memory structure. Records are framed body-first with the length last, so a crash leaves a zero rather than a plausible header in front of nothing; CRC32C per record. Compaction rewrites live records and is bounded by the number of keys, not by the volume of data |
| Data | one file per object under a name that is **not** derived from the key — a UUID over two directory levels. Deleting is `unlink`, so there are no holes and no data compaction at all |
| Durability | the file is written and `fsync`ed *before* the index record, so the only thing a crash can leave behind is an orphan, which a background sweep collects. The other order leaves a dangling reference, which is a `500` on a key the server itself said exists |
| Listing | ordered by unsigned byte comparison; `delimiter` jumps past a group instead of walking it, which is the one thing a directory tree gets for free |
| HTTP | its own HTTP/1.1 on a selector loop, because `transferTo` needs a real `SocketChannel` and the interesting half of this HTTP is S3-specific anyway |
| TLS | somebody else's, in front |

| Module | |
|---|---|
| `bochka-core` | storage: index, metadata journal, object files, recovery. Knows nothing about S3 |
| `bochka-s3` | the protocol: request parsing, SigV4 verification including `aws-chunked`, XML, errors. Knows nothing about sockets |
| `bochka-http` | its own HTTP/1.1: selector, `Expect`, `Range`, keep-alive, `sendfile` |
| `bochka-app` | running it: configuration, request logging, housekeeping, the shipped runtime profile |
| `bochka-embedded` | start a server on a random port from a test, stop it after |
| `bochka-benchmark` | the numbers |

## Acceptance

The first milestone is not "seven operations". It is **one `PUT`, accepted four different ways** —
signed payload, `UNSIGNED-PAYLOAD`, `STREAMING-AWS4-HMAC-SHA256-PAYLOAD`, and
`STREAMING-UNSIGNED-PAYLOAD-TRAILER` — plus `Expect: 100-continue`, verified by four clients that
share no code with this one. Everything else is work with known answers; the input path is the only
place where being wrong means rewriting it.

Three independent levels, because each is blind to what the others catch:

1. **Signatures** — the 34 official AWS SigV4 vectors, run in the *verifying* direction, plus the
   S3-mode vectors. No network, every run, part of the gate.
2. **Live clients, from the first milestone rather than after it** — `aws-cli`, `boto3`, `rclone`,
   `mc`. This is the only level where the first two findings above show up at all. It also runs
   them at once: eight uploads of eight keys, and eight uploads of *one* key, which must leave an
   object equal to one of them rather than a mixture of two.
3. **Somebody else's suite as a counter** — `ceph/s3-tests`, wired up early not to pass but to
   produce a number, with an exclusion list committed to the repository and a reason on every line.
   The count of enabled tests is printed next to the percentage, because otherwise a rising score
   and a shrinking suite look identical.

s3kn is in level 2 as well — `ci/s3kn.sh` runs its live tests against bochka, 20 of 21 today — but
it is the one client whose result is never the metric. Its author is this project's author, and
testing a server with your own client is the weakest check available — it signs its bodies the one simple
way, so it never exercises the thing most likely to be broken, and it does that while looking
green. It stays in the set as a second independent implementation of the signature and as presign
coverage. It is not the metric.

The one it does not pass is a disagreement rather than a defect, which is worth naming because it
is the shape a shared author produces: s3kn asserts `411` for a `PUT` framed chunked with no
`Content-Length`, and bochka has answered `200` since M12 on the grounds that a chunked body
states its length as it goes. The API model settles neither side. Written down as a question with
a criterion rather than fixed by whichever repository was edited last.

That percentage is also the one number here that means something outside this repository: how much
of `ceph/s3-tests` a single-process JVM store passes is comparable with other implementations,
which is true of no benchmark this project could run on its own. So it is published as soon as it
exists, ahead of everything else:

> **490 of 744 passed (66%)** — `ceph/s3-tests` at `5522d1c`, `./ci/s3-tests.sh`.
>
> **498 of 744 (67%)** — the same run with `BOCHKA_ANONYMOUS=1`.

There are two because the number measures a configuration, not a codebase. Anonymous access ships
turned off, so the run anybody gets by default cannot see it; the second number is what the switch
is worth, taken by the same harness (`S3TESTS_ANONYMOUS=1 ./ci/s3-tests.sh`). Publishing one of
them with a footnote would overstate the default and understate the work.

**224 of the 254 remaining failures** are things this store says in
["What bochka is not"](#what-bochka-is-not) that it will never have — server-side encryption,
grants to named users, IAM, storage classes. Every failure is classified with a reason
([docs/s3-tests.md](docs/s3-tests.md)), and one nobody has classified is reported by name as
`unclassified` rather than folded into a category. That count is zero.

**None are defects**, and the nine that were are worth a sentence: they were found by re-reading
the classification rather than by running anything, because three families sat behind reasons that
had stopped being true when the features arrived. A label saying "out of scope" over a defect is
worse than no label — the unclassified count is watched, and a closed-looking question is not.

**Thirty are in scope and not done**, and they split three ways. Four the server passes with
anonymous access switched on, which is a configuration rather than a gap, and they are labelled
`off-by-default` so that the label can be checked by flipping the switch. Twenty-four are work with
an address in the backlog: `PublicAccessBlock` and `GetBucketPolicyStatus`, both of which became
reachable the moment bucket policies existed, a POST form carrying no policy at all, and an
`OPTIONS` that is not a preflight. **`PublicAccessBlock` has since been built** — all four flags,
enforced rather than stored — so these counts describe the run before it and not the code in this
tree; the run that turns that into numbers is the next one. **Two** are there because a decision is unmade rather than
because work is: a key holding C1 control characters and the round trip of non-ASCII metadata.
A third — the ETag of a re-sent encrypted part — was one of these until the decision got made:
an encrypted object's ETag is an HMAC of its plaintext under the client's key, which is the only
shape that is both stable across re-sends and useless to anybody reading a listing.
Each names in the classification file what would settle it, because a "deferred" with no criterion
is a "deferred" for ever. That whole distinction was itself a finding — those entries once sat in
the classification file while the backlog said there was nothing to do, which is two documents
disagreeing about the same thing.

Counted against what is in scope the score is 490 of 520 — 94% — and that number is here as a
warning rather than a boast: its denominator is chosen by this repository, and 99% of your own
scope list is available to anyone willing to lengthen the list. **63% is the honest one because
somebody else chose it.** What the number is for is the direction it moves, which is why the count
of tests that ran is printed beside it — a rising score and a shrinking suite look identical
otherwise.

## Running it

```bash
docker run -d --name bochka \
  -u 1000:1000 \
  -v /srv/bochka:/var/lib/bochka \
  -p 127.0.0.1:9000:9000 \
  -e BOCHKA_KEYS='youraccesskey:yoursecretkey' \
  ghcr.io/youndie/bochka:latest
```

Bound to the loopback on purpose, with nginx in front for TLS — that is the architecture rather
than a convenience, because terminating TLS inside the JVM would cost the read path this whole
project is built around. [deploy/](deploy/) has the configuration and the reasoning.

There is a Helm chart in [deploy/helm/bochka](deploy/helm/bochka) with a harness that installs it
into a real kubelet, and from the next release it is published as an OCI package beside the image.
What it is for is stated rather than left to be inferred: production on one machine. Helm is not
how anybody runs a store in a test — that is the section below — so there is no mode in which the
volume is optional.

**`GET /-/healthy` answers `200` to anyone who can reach the port, with no signature.** It is the
one handle of this kind, it exists for an orchestrator, and the path is `-` because no bucket may
be called that. What it proves is narrow and worth stating: the process parsed a request, routed it
and answered, and it could not have started answering before the journal was replayed. It does not
prove the disk is writable.

**A setting bochka does not recognise stops it from starting.** `BOCHKA_DATADIR` instead of
`BOCHKA_DATA_DIR` means the objects are in a temporary directory and nothing about the running
process says so, so instead the process refuses, names the typo and suggests the real one. The
cost is real and worth stating: a `BOCHKA_*` variable set for some other purpose will stop this
server.

## Starting it inside a test

The other half of the niche: on the JVM, "an S3 endpoint you can start in a test" is currently a
mock, and a mock answers what it was told to answer. This is the same server the image runs —
same signature verification, same four body framings, same storage.

```kotlin
repositories { maven("https://reposilite.kotlin.website/snapshots") }
dependencies { testImplementation("io.github.youndie.bochka:bochka-embedded:0.2.0") }
```

A store per test class, reset between tests — what is expensive is the start, not the state:

```kotlin
class ReportsTest {
    companion object {
        @JvmField @RegisterExtension val bochka = BochkaExtension()   // io.github.youndie.bochka:bochka-junit
    }

    @Test fun `retries a 503`() {
        bochka.bochka.put("reports", "seed.csv", "id,name\n".toByteArray())  // start from a state
        bochka.bochka.failNext(503)                                            // make the client survive one
        // …point your SDK at bochka.endpoint and assert your own retry logic
    }
}
```

`failNext` is the one thing a real store cannot do and a test double should: client code nobody can
knock over is client code whose retries are untested. It goes back to answering normally by itself,
and `reset()` clears any that are left.

**No TLS inside the process, and that is a decision rather than a gap.** Terminating TLS here means
wrapping the socket, and a wrapper silently removes the `transferTo` path this whole server is built
around — there is a test guarding exactly that. A test that needs TLS puts a terminator in front, the
way the deployment does.

```kotlin
Bochka.start().use { bochka ->
    val s3 = S3Client.builder()
        .endpointOverride(URI.create(bochka.endpoint))
        .credentialsProvider { AwsBasicCredentials.create(bochka.accessKeyId, bochka.secretKey) }
        .forcePathStyle(true)
        .build()
    // ...
}
```

It picks its own port, makes its own directory and removes it on close. It does not `fsync` by
default — a test that flushes every write is measuring the disk — and `durable = true` says so
when that is the thing being tested.

bochka is compiled to JVM 25 bytecode, so a consumer needs a toolchain that can target 25:
`plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }` in
`settings.gradle.kts`. Without it Gradle cannot provision the JDK, Kotlin silently falls back to
its own highest target, and the build fails with a message about `compileJava` and `compileKotlin`
disagreeing — which reads like a bug in your project and is a missing line.

## What bochka is not

- **Not a cluster.** One process, one disk, no replication and no failover. A node that dies takes
  its objects with it until it comes back.
- **Not optimised for small objects.** One file per object means one inode and one 4 KiB block
  apiece; a one-byte object occupies a page. Packing small objects into a shared file would bring
  back holes and data compaction, which is the complexity this design exists without. Said here
  on day one rather than discovered later.
- **Not unbounded in object count.** The index keeps every key in memory, so the ceiling is memory,
  and it is a published number rather than a surprise: `Runtime.maxMemory() * 0.5 / 650`, which on
  the shipped `-Xmx512M` profile is **399 215 objects**, printed as `object ceiling` on the first
  line of the log. A store at its ceiling refuses new keys with `507 InsufficientStorage` and goes
  on serving everything it already holds; a store whose index no longer fits the heap it was given
  refuses to open at all, instead of degrading into swap.
- **Not multi-class storage.** One disk means one storage class, so a lifecycle rule carrying a
  `Transition` is refused by name rather than stored and never acted on.
- **Not an identity system.** Access keys are a static list in the configuration; no IAM and no
  STS. What it does have is the part that needs no user table: a bucket and an object belong to
  the key that made them, the six canned ACLs decide what other keys may do with them, a bucket
  policy can hand a capability to a key the ACL never named, and `PublicAccessBlock` can take the
  public half of both away again. This bullet said "no bucket policies" for a milestone after they
  arrived, which is the failure mode a section about what does not exist has by nature. A grant to a named user is refused by name — it would be a permission language over people
  this server does not know. An unsigned request is refused whatever the ACL says until
  `BOCHKA_ANONYMOUS=1` is set, and then a `public-read` bucket answers one — the switch adds a
  capability, the ACL still decides. Only a request carrying *no* credentials can become anonymous:
  a signature that fails to verify stays a failure at every setting.
- **Not encrypted with a key of its own.** No TLS termination and no SSE-S3 or SSE-KMS: the first
  would wrap the socket and the second would make this process a keeper of secrets, with rotation
  and an audit trail behind it. **SSE-C is there**, and it is the one place the trade is worth it:
  the key arrives with the request, the server keeps only its MD5 and an IV, and an object nobody
  encrypted still goes out by `transferTo`. What an encrypted one costs is measured rather than
  implied — the cipher doubles the user-space read path, 2.04× ([docs/measurements.md](docs/measurements.md))
  — and only whoever sends a key pays it.
- **Not compared with anything.** The read path is measured — numbers, host and filesystem are in
  [docs/measurements.md](docs/measurements.md), and a milestone that changes the hot path does not
  close without them — but nothing here has been benchmarked *against another store*, and no
  number below should be read as one.

## Documentation

[docs/](docs/README.md) — layered documentation, written in Russian; code, KDoc and comments are in
English.

Read [research-architecture](docs/research/research-architecture.md) before changing anything. It
separates what was verified against a source from what was assumed, and records which premises of
the original brief did not survive contact with the sources — two of them did not.
[BACKLOG.md](BACKLOG.md) holds the work items, and per milestone, what came out differently than
planned.

## License

MIT. See [LICENSE](LICENSE).
