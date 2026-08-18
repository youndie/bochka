# The image is built from a distribution that already exists, not from source.
#
# `./gradlew :bochka-app:installDist` runs the gate first, so `docker build` cannot produce an
# image from code that does not pass `check` — a multi-stage build that compiles inside the image
# can, and then the image is the only place the failure is visible.
#
#   ./gradlew check :bochka-app:installDist
#   docker build -t bochka .
#
# One stage, therefore, and a JRE rather than a JDK: the compiler has no business in a running
# server, and the difference is about 150 MiB of attack surface.
FROM eclipse-temurin:25-jre

# A named user, and the data directory is theirs. Running as root inside a container is the
# difference between a bug in this server and a bug that owns the volume — and a container that
# writes as root leaves files on a bind mount that the host user cannot delete.
#
# UID 1000 specifically, and the base image already has somebody there — Temurin 25 ships an
# `ubuntu` user at 1000. It is removed rather than worked around: 1000 is the first id a desktop
# Linux gives a human, so it is the id a bind-mounted directory on somebody's machine belongs to,
# and a container that writes as anything else cannot write into it at all.
RUN userdel --remove ubuntu 2>/dev/null || true; \
    groupadd --gid 1000 bochka && \
    useradd --uid 1000 --gid bochka --home-dir /var/lib/bochka --shell /usr/sbin/nologin bochka && \
    mkdir -p /var/lib/bochka && chown bochka:bochka /var/lib/bochka

COPY --chown=bochka:bochka bochka-app/build/install/bochka-app /opt/bochka

# The number, not the name, and it is the same user either way — `bochka` is still in /etc/passwd,
# still owns both directories, and `id` inside the container still answers `uid=1000(bochka)`. What
# changes is what the image *declares*. An orchestrator asked to refuse a root container has to
# decide, before starting anything, whether this image runs as root; it cannot resolve a name
# against a passwd file inside an image it has not started yet, and it declines to guess. So a
# `USER bochka` image will not start under `runAsNonRoot: true` unless whoever deploys it also
# repeats the uid — which makes the image depend on its deployment agreeing with it about a number
# that is already baked in here.
USER 1000:1000
WORKDIR /var/lib/bochka
VOLUME ["/var/lib/bochka"]

ENV BOCHKA_DATA_DIR=/var/lib/bochka \
    BOCHKA_BIND_ADDRESS=0.0.0.0 \
    BOCHKA_PORT=9000
EXPOSE 9000

# `exec` in the start script means the JVM is PID 1 and gets the signals; without it the shell is
# PID 1, `docker stop` reaches the shell, and the JVM is killed ten seconds later by the timeout
# with whatever it was doing unfinished. Gradle's generated script already execs.
ENTRYPOINT ["/opt/bochka/bin/bochka-app"]

# Labels are inherited, so an image without its own does not have none — it has the base image's.
# Unlabelled, this one tells everything that reads labels (a registry's package page, a scanner, a
# dependency bot) that it is Ubuntu 26.04 by Canonical: true of the floor it stands on and of
# nothing else here. `source` is also the mechanism by which a package on a registry is attached to
# the repository that built it, so without it the package page does not know where it came from.
#
# VERSION is an argument rather than a constant so that a build can stamp the tag it publishes
# under. It defaults to `dev`, which is at least true of a build that stamps nothing, whereas the
# inherited `26.04` is a statement about a different product.
#
# Last in the file on purpose: an argument changes on every release, and everything below the layer
# that reads it has to be rebuilt. Here that is the metadata alone — the user and the application
# are already cached by the time the version is known.
ARG VERSION=dev
LABEL org.opencontainers.image.title="bochka" \
      org.opencontainers.image.description="An S3-compatible object store in Kotlin/JVM: one process, one node, one disk" \
      org.opencontainers.image.source="https://github.com/youndie/bochka" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${VERSION}"
