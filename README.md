# bochka

[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![jvm](https://img.shields.io/badge/JVM-25-blue?logoColor=white)](https://openjdk.org/projects/jdk/25/)
[![status](https://img.shields.io/badge/status-early-orange)](BACKLOG.md)
[![s3-tests](https://img.shields.io/badge/ceph%2Fs3--tests-229%2F744-yellow)](ci/s3-tests.sh)
[![license](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

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

Objects can also be copied server-side, read conditionally (`If-Match` and the rest), and
listed with owners.

Not built: delivery — a distribution, an image and published artifacts — and the seven things the
compatibility run found and nobody has claimed. [BACKLOG.md](BACKLOG.md) says which milestone each
is, and every closed one ends with what came out differently than planned.

Anything below describing behaviour bochka does not have says so — that is the project's first
rule, `main` describes what exists.

## Measured, not assumed

The whole read path exists for one property, and identical bytes come out either way — so it is
measured rather than believed. On ext4, median of seven runs of two gibibytes each, with the
spread printed beside every number ([docs/measurements.md](docs/measurements.md)):

> **Reading into the heap costs 5.3× the processor per byte that `transferTo` does.**

Two of the three measurements came out against the plan, which is the more useful half. The
buffer the upload path uses turns out not to matter — size and kind are both inside the noise, so
nothing was changed — and `splice(2)` through FFM is not being introduced, because the most it
could remove is a quarter of a core at the rate a single disk sustains.

## How many objects, stated rather than discovered

Every key lives in memory, so the number of objects is bounded by the heap whether anybody says so
or not. Measured at **650 bytes of index per object** (586 for a forty-byte key, 647 for a
hundred-byte one — the larger is what the code uses), with half the heap allowed to be index:

| `-Xmx` | Objects |
|---|---|
| 64 MiB, the development profile | ~51 600 |
| **512 MiB, what ships** | **~413 000** |
| 4 GiB | ~3 300 000 |

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
  in front of the process — which loses nothing, because nginx does the same zero-copy send over
  TLS itself (`SSL_sendfile`, guarded by `BIO_get_ktls_send`, in `ngx_event_openssl.c`). The way
  inside exists but means replacing the TLS stack, not adding a flag.

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
| `bochka-app` | running it: configuration, metrics, distribution, health check |
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

s3kn is in level 2 as well — `ci/s3kn.sh` runs its live tests against bochka, 15 of 21 today — but
it is the one client whose result is never the metric. Its author is this project's author, and
testing a server with your own client is the weakest check available — it signs its bodies the one simple
way, so it never exercises the thing most likely to be broken, and it does that while looking
green. It stays in the set as a second independent implementation of the signature and as presign
coverage. It is not the metric.

That percentage is also the one number here that means something outside this repository: how much
of `ceph/s3-tests` a single-process JVM store passes is comparable with other implementations,
which is true of no benchmark this project could run on its own. So it is published as soon as it
exists, ahead of everything else:

> **229 of 744 passed (30%)** — `ceph/s3-tests` at `5522d1c`, 3m09s, `./ci/s3-tests.sh`.

Low, and it should be: most of the 515 remaining failures are things this store says in
["What bochka is not"](#what-bochka-is-not) that it will never have — encryption, ACLs,
versioning, lifecycle, policies. Every failure is classified with a reason
([docs/s3-tests.md](docs/s3-tests.md)), and a failure nobody has classified is reported by name
as `unclassified` rather than folded into a category. That count is currently zero, which is the
claim worth making: 52 are deferred and **1 is a defect**, named. What the number is for is the direction it moves, which is why
the count of tests that ran is printed beside it — a rising score and a shrinking suite look
identical otherwise.

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
dependencies { testImplementation("io.github.youndie.bochka:bochka-embedded:<version>") }
```

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
  and it will be published as a number — objects per MiB of heap — once it has been measured. A
  store that cannot fit its index says so at startup instead of degrading into swap.
- **Not versioned.** One version per key. Versioning would make the index key composite, which is
  a different project.
- **Not an identity system.** Access keys are a static list in the configuration; no IAM, no bucket
  policies, no STS.
- **Not encrypted in-process.** No TLS termination and no server-side encryption: both mean
  touching the bytes on the read path, which is what the zero-copy path exists not to do.
- **Not benchmarked.** There is nothing to benchmark yet. When there is, a milestone that changed
  the hot path will not close without a number, a host and a filesystem next to it.

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
