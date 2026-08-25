# bochka

[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![jvm](https://img.shields.io/badge/JVM-25-blue?logoColor=white)](https://openjdk.org/projects/jdk/25/)
[![status](https://img.shields.io/badge/status-early-orange)](BACKLOG.md)
[![s3-tests](https://img.shields.io/badge/ceph%2Fs3--tests-518%2F744-green)](ci/s3-tests.sh)
[![license](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![release](https://img.shields.io/badge/release-v0.4.0-blue)](https://github.com/youndie/bochka/releases/tag/v0.4.0)

An S3-compatible object store in Kotlin/JVM. One process, one node, one disk: no erasure coding,
no replication, no quorum. On the outside, the protocol `aws s3`, `mc`, `boto3` and every S3
library already speak; on the inside, an index ordered by the raw bytes of the key, a file per
object, and a GET path that is `sendfile` from that file straight to the socket.

The niche is not "a MinIO replacement". It is the slot that on the JVM is currently empty: what
lives there today is a proxy (`s3proxy` translates S3 onto other backends through jclouds) and a
mock (`S3Mock`, itself Kotlin, shipped as a Testcontainer and a JUnit extension). A store that
actually keeps what you give it is missing — and, because it has to be small to be worth writing,
it can also be the thing you start inside a test.

## Run it

```bash
docker run -d --name bochka -u 1000:1000 \
  -v /srv/bochka:/var/lib/bochka -p 127.0.0.1:9000:9000 \
  -e BOCHKA_KEYS='youraccesskey:yoursecretkey' \
  ghcr.io/youndie/bochka:v0.4.0
```

Bound to the loopback on purpose, with your own TLS terminator in front — terminating TLS inside
the JVM would cost the read path this whole project is built around. [deploy/](deploy/) has the
configuration and the reasoning, including what changes if you put nginx there.

There is a Helm chart, published beside the image and versioned on its own:

```bash
helm install bochka oci://ghcr.io/youndie/charts/bochka --version 0.3.0 \
  --set auth.keys[0].id=youraccesskey --set auth.keys[0].secret=yoursecretkey
```

It is for production on one machine. Almost everything it does is a refusal — a values file that
would install a store nobody can reach, or one the orchestrator would turn into two writers, does
not render at all.

## Or start it inside a test

The other half of the niche. On the JVM, "an S3 endpoint you can start in a test" is currently a
mock, and a mock answers what it was told to answer. This is the same server the image runs — same
signature verification, same four body framings, same storage.

```kotlin
repositories { maven("https://reposilite.kotlin.website/snapshots") }
dependencies { testImplementation("io.github.youndie.bochka:bochka-embedded:0.4.0") }
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

It picks its own port, makes its own directory and removes it on close. There is a JUnit 5
extension beside it, and a mode that hands out prepared answers and refusals for the tests that
need a server to misbehave.

## What it does

Objects, with `Range`, metadata, checksums, server-side copy, conditional reads and writes,
`GetObjectAttributes` and `partNumber` on a read. Listing in both versions of the operation, with
`delimiter` and pagination, ordered by the unsigned bytes of the key. Multipart upload including
`UploadPartCopy`. Bucket and object tags, CORS with preflight, and browser `POST` form uploads with
a policy and either signature version.

Versioning end to end — delete markers, reads and deletes by `versionId`, `ListObjectVersions` in
pages. Object lock with retention in both modes and legal hold. Lifecycle rules that are **applied**
rather than stored: objects and noncurrent versions expire, orphaned delete markers and abandoned
uploads go, and `x-amz-expiration` says when.

Permissions in the shape that fits a store whose users are its access keys: a key scope narrowing
what a key may do at all, an owner per bucket and per version, canned ACLs stored and enforced,
bucket policies — the first layer that **grants** rather than takes away — and `PublicAccessBlock`,
whose four switches each do something rather than being recorded. A grant to a named user is
refused by name: it would be a permission language over people this server does not know.

`SSE-C` on single and multipart uploads: the key arrives with the request, the server keeps its MD5
and an IV and never the key. An object nobody encrypted is untouched by it — same `transferTo`,
same cost.

[BACKLOG.md](BACKLOG.md) says which milestone each of these is, and every closed milestone ends
with what came out differently than planned.

## The numbers, and where they come from

> **518 of 744** `ceph/s3-tests` as it ships, **533 of 744** with `BOCHKA_ANONYMOUS=1`, at suite
> revision `5522d1c`. Every remaining failure is classified with a reason, and the count of tests
> that ran is printed beside the percentage — a rising score and a shrinking suite look identical
> otherwise.

> **Reading into the heap costs 7.6–8.0× the processor per byte that `transferTo` does**, across a
> real network card between two machines. Over loopback the same comparison says 5.3×, and the
> difference is the point: loopback has no device in it.

Every feature is measured against that path rather than assumed to be free, and the measurements
that came out **against** the plan are the more useful half — the upload buffer turned out not to
matter, `splice(2)` was not worth introducing, and the reason this project terminates TLS outside
the process turned out to be a different reason than the one written down for a year. Numbers,
host, filesystem and the spread are in [docs/measurements.md](docs/measurements.md); a milestone
that touches the hot path does not close without them.

## How many objects fit

Every key lives in memory, so the count is bounded by the heap whether anybody says so or not.
Measured at **650 bytes of index per entry**, with half the heap allowed to be index:

| profile | heap | objects | the chart asks for |
|---|---|---|---|
| `small` | `-Xmx128M` | **99 816** | 320Mi |
| `default`, what ships | `-Xmx512M` | **399 215** | 768Mi |

Two whole runtime profiles in the distribution, chosen by `heapProfile` in the chart — not a heap
size, because the heap is what the ceiling is derived from, so the two are two promises rather than
a tuning knob. The smaller one exists because the heap and the page cache come out of the same
cgroup and this read path wants a **hot** file: a 300 MiB object is served in 132 ms under the
small profile against 455 under the default. Both memory floors are measured under load with the
index at that profile's own ceiling, not added up.

Three things about that number are worth knowing before it surprises you. It counts **versions**,
so a versioning bucket holds as many objects as its history allows rather than as many as the table
says. Reaching it is a `507 InsufficientStorage` on new keys and nothing else — overwrites, reads
and deletes go on, because a full store has to be able to make itself smaller. And starting with an
index that no longer fits is a **refusal to start** rather than a slide into swap: a process that
comes up and then thrashes looks like a slow disk to everybody who did not write the index.

Larger heaps have been measured and are not recommended: the live set *is* the index, so a full
collection grows with it — 7.56 seconds of stop-the-world at 4 GiB is a request timeout, not a
hiccup. The ceiling is also a property of the collector rather than of `-Xmx`, which is why the
startup log names both.

## How it is checked

Four levels, because each is blind to what the others catch:

- **the gate** — `./gradlew check`, including the 34 official AWS SigV4 vectors run in the
  *verifying* direction, and a bytecode check that fails on a lock in the read path;
- **other people's clients** — `aws-cli`, `boto3`, `mc` and `rclone` as containers over a real
  socket ([`ci/live-clients.sh`](ci/live-clients.sh)), plus `io.minio:minio` inside the gate,
  because the embedded mode's client is a library and cannot be a container;
- **somebody else's suite** — [`ci/s3-tests.sh`](ci/s3-tests.sh), which can also be pointed at a
  deployment so the number includes whatever proxies it ([docs/s3-tests.md](docs/s3-tests.md));
- **crash and cluster** — a test that kills the JVM with `SIGKILL` mid-write and demands that
  everything the log admitted to still reads back, and a chart harness that installs into a real
  kubelet rather than rendering YAML.

The first milestone was not "seven operations" but **one `PUT` accepted four different ways** —
signed, `UNSIGNED-PAYLOAD`, and both streaming framings — verified by clients that share no code
with this one. Everything else is work with known answers; the input path is the only place where
being wrong means rewriting it.

## Internals

| | |
|---|---|
| Index | bitcask: an append-only log of index mutations plus an ordered in-memory structure. Records are framed body-first with the length last, so a crash leaves a zero rather than a plausible header in front of nothing; CRC32C per record. Compaction is bounded by the number of keys, not by the volume of data |
| Data | one file per object under a name that is **not** derived from the key — a UUID over two directory levels. Deleting is `unlink`, so there are no holes and no data compaction at all |
| Durability | the file is written and `fsync`ed *before* the index record, so the only thing a crash can leave behind is an orphan, which a background sweep collects. The other order leaves a dangling reference, which is a `500` on a key the server itself said exists |
| Listing | ordered by unsigned byte comparison; `delimiter` jumps past a group instead of walking it |
| HTTP | its own HTTP/1.1 on a selector loop, because `transferTo` needs a real `SocketChannel` and the interesting half of this HTTP is S3-specific anyway |
| TLS | somebody else's, in front |

| Module | |
|---|---|
| `bochka-core` | storage: index, metadata journal, object files, recovery. Knows nothing about S3 |
| `bochka-s3` | the protocol: request parsing, SigV4 including `aws-chunked`, XML, errors. Knows nothing about sockets |
| `bochka-http` | its own HTTP/1.1: selector, `Expect`, `Range`, keep-alive, `sendfile` |
| `bochka-app` | running it: configuration, request logging, housekeeping, the runtime profiles |
| `bochka-embedded` | start a server on a random port from a test, stop it after |
| `bochka-benchmark` | the numbers |

Three findings from the research explain most of those choices, and each is verified against a
source rather than remembered: `aws s3 cp` sends its body as signed `aws-chunked` frames and
deletes `Content-Length`, so a server reading "Content-Length bytes off the socket" does not work
with it at all; the S3 keyspace does not fit in a filesystem, which is why an object on disk is a
UUID and the key lives only in the index; and `String.compareTo` sorts `😀` before `！` while UTF-8
byte order is the reverse, which is why keys here are bytes compared unsigned. The rest, including
the premises of the original brief that did not survive contact with the sources, is in
[research-architecture](docs/research/research-architecture.md).

## What it is not

- **Not a cluster.** One process, one volume: no replication, no failover, no rebalancing. The
  chart refuses `ReadWriteMany` and more than one replica rather than pretending otherwise.
- **Not unbounded.** The ceiling is above, and it is published rather than discovered.
- **Not a TLS terminator**, and not encrypted with a key of its own — no SSE-S3 or SSE-KMS, because
  that would make this process a keeper of secrets with rotation and an audit trail behind it.
  `SSE-C` is there, where the key belongs to whoever sends it.
- **Not an identity system.** Access keys are a static list in the configuration; no IAM, no STS.
- **Not compared with anything.** The read path is measured — host, filesystem and spread included
  — but nothing here has been benchmarked *against another store*, and no number above should be
  read as one.

## Documentation

[docs/](docs/README.md) — layered documentation, written in Russian; code, KDoc and comments are in
English.

Read [research-architecture](docs/research/research-architecture.md) before changing anything. It
separates what was verified against a source from what was assumed, and records which premises of
the original brief did not survive — two of them did not. [BACKLOG.md](BACKLOG.md) holds the work
items and, per milestone, what came out differently than planned.

## License

MIT. See [LICENSE](LICENSE).
