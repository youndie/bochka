# bochka

An S3-compatible object store in one process, on one disk. No cluster, no replication, no quorum —
and no mock either: this is a real store that keeps what you give it, small enough to start inside
a test.

Source, issues and the rest of the documentation:
**[github.com/youndie/bochka](https://github.com/youndie/bochka)**

```bash
docker run -d --name bochka -u 1000:1000 \
  -v /srv/bochka:/var/lib/bochka -p 127.0.0.1:9000:9000 \
  -e BOCHKA_KEYS='youraccesskey:yoursecretkey' \
  youndie/bochka:v0.7.0
```

Then point any S3 client at `http://127.0.0.1:9000`:

```bash
aws --endpoint-url http://127.0.0.1:9000 s3 mb s3://photos
aws --endpoint-url http://127.0.0.1:9000 s3 cp cat.jpg s3://photos/
```

`aws-cli`, `boto3`, `rclone`, `mc`, the AWS SDKs and MinIO's clients all work against it unchanged;
519 of 744 cases of `ceph/s3-tests` pass, and every remaining failure is classified with a reason
in the repository.

## Bound to the loopback on purpose

Put your own TLS terminator in front. Terminating TLS inside the JVM would cost the read path this
project is built around — a `GET` is `sendfile` from the object's file straight to the socket.
There is an nginx configuration and the reasoning in
[deploy/](https://github.com/youndie/bochka/tree/main/deploy).

## Tags

`v0.7.0` and every earlier release; `latest` moves with them. **Two architectures**,
`linux/amd64` and `linux/arm64` — the same manifest that is published at
`ghcr.io/youndie/bochka`, copied by digest rather than built a second time.

Pin the version rather than `latest`: a tag that moves is not something a deployment can roll back
to.

## Configuration

Everything is an environment variable, and an unknown `BOCHKA_*` name is **refused at startup**
rather than ignored — a setting nobody reads is a setting somebody thinks is in effect.

| | |
|---|---|
| `BOCHKA_KEYS` | access keys as `id:secret,id2:secret2`. **Two published defaults if unset** — set this |
| `BOCHKA_KEYS_FILE` | a file holding the same thing, for a Secret mounted rather than put in the environment |
| `BOCHKA_KEY_SCOPES` | narrow a key: `id=ro`, `id=rw@bucket\|bucket` |
| `BOCHKA_DATA_DIR` | where objects and the index live. `/var/lib/bochka` in this image, and a volume |
| `BOCHKA_PORT` / `BOCHKA_BIND_ADDRESS` | `9000` and `0.0.0.0` in this image |
| `BOCHKA_REGION` | the region name this deployment answers with |
| `BOCHKA_VIRTUAL_HOST_SUFFIXES` | domains under which a leading label is a bucket name |
| `BOCHKA_ANONYMOUS` | `1` lets an unsigned request through to the ACL; off, an unsigned request is `403` whatever the ACL says |
| `BOCHKA_MAX_OBJECTS` | the ceiling on objects; derived from the heap if unset |
| `BOCHKA_MAX_CONNECTIONS` | how many connections may be live at once before `503`; derived from the heap if unset |
| `BOCHKA_HEAD_TIMEOUT_SECONDS` | how long a request head may take to arrive before `408` |
| `BOCHKA_BODY_IDLE_TIMEOUT_SECONDS` | how long a body may go quiet between reads before `408` |
| `BOCHKA_HOUSEKEEPING_MINUTES` | how often to compact and sweep; `0` to never |
| `BOCHKA_LIFECYCLE_DAY_SECONDS` | how long a lifecycle rule's "day" lasts |
| `BOCHKA_ACCEL_REDIRECT` | hand whole-object reads to the terminator in front by this internal prefix |
| `BOCHKA_LOG` | `1` to print a line per request |

## Operating it

* `GET /-/healthy` — a liveness handle, and what the Helm chart's probe uses;
* `GET /-/stats` — what the store holds, including how close it is to its object ceiling. That
  number is worth watching before it is reached rather than after;
* startup prints how long it took and what took it: the journal's size, how many records were
  recovered, whether a torn tail was discarded;
* `docker stop` **finishes what it accepted** — a request already in flight is served to its end
  rather than cancelled — and then exits. Data is identical either way: an acknowledged object
  survives `docker kill` too.

Runs as uid 1000 and needs no root. The volume must be writable by that uid.

## What it is not

One node and one disk. There is no replication, no failover and no erasure coding, and there will
not be: the whole design is a single process whose read path is a file descriptor and a socket.
If you need a cluster, you need a different thing.

## Helm

```bash
helm install bochka oci://ghcr.io/youndie/charts/bochka --version 0.4.1 \
  --set auth.keys[0].id=youraccesskey --set auth.keys[0].secret=yoursecretkey
```

The chart defaults to `ghcr.io/youndie/bochka` rather than to Docker Hub, because Docker Hub's
anonymous pull limits are the stricter of the two.

## In a test

There is a JVM library that starts this same server in-process, on Maven Central:

```kotlin
repositories { mavenCentral() }
dependencies { testImplementation("io.github.youndie.bochka:bochka-embedded:0.7.0") }
```

MIT licensed.
