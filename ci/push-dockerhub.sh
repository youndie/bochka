#!/usr/bin/env bash
# Copies a published image to Docker Hub, and checks it is the same image.
#
# NOT A SECOND BUILD. Building the same tree twice gives different bytes — timestamps, a base image
# that moved under it — and both would be published under one number with nothing to tell them
# apart. `imagetools create` copies the manifest by digest: the layers are not rebuilt, both
# architectures come along, and what lands on Docker Hub is the thing that was tested at ghcr.
#
# The registries are asked afterwards whether the two names now point at the same digest, because a
# copy that succeeded and a copy that silently made something else look identical from here.
#
#   ./ci/push-dockerhub.sh v0.7.0
#
# Both registries must already be logged in; this script never sees a credential.
set -uo pipefail

readonly TAG=${1:?usage: push-dockerhub.sh <tag, for example v0.7.0>}
readonly SOURCE=${BOCHKA_SOURCE_IMAGE:-ghcr.io/youndie/bochka}
readonly TARGET=${BOCHKA_DOCKERHUB_IMAGE:-docker.io/youndie/bochka}

case $TAG in
  v*) ;;
  # The image tags carry the `v`; a Maven coordinate does not. Refused rather than accepted and
  # published, because a registry tag is cheap to make and impossible to un-make quietly.
  *) echo "the image tag carries the v: v$TAG" >&2; exit 3 ;;
esac

digest_of() {
  docker buildx imagetools inspect "$1" --format '{{json .Manifest.Digest}}' 2>/dev/null | tr -d '"'
}

source_digest=$(digest_of "$SOURCE:$TAG")
if [ -z "$source_digest" ]; then
  echo "$SOURCE:$TAG is not there, so there is nothing to copy. Publish the release first." >&2
  exit 3
fi
echo "$SOURCE:$TAG is $source_digest"

# `latest` moves with the release, exactly as it does at ghcr. A tag that moves is not something to
# pin, which is why the version is written beside it rather than instead of it.
docker buildx imagetools create \
  -t "$TARGET:$TAG" \
  -t "$TARGET:latest" \
  "$SOURCE:$TAG" || { echo "the copy failed; nothing was published to $TARGET" >&2; exit 1; }

target_digest=$(digest_of "$TARGET:$TAG")
if [ "$target_digest" != "$source_digest" ]; then
  echo "the copy landed as $target_digest and the source is $source_digest: $TARGET:$TAG is not" >&2
  echo "the image that was tested" >&2
  exit 1
fi

# AND STILL TWO ARCHITECTURES. A copy that flattened the index to one manifest would answer with a
# digest of its own — caught above — but a registry that rewrites an index into a single platform
# on the way in would not, and the failure arrives at a consumer as `exec format error`, which reads
# as a broken image rather than as the wrong architecture (M-137).
platforms=$(docker buildx imagetools inspect "$TARGET:$TAG" --format '{{range .Manifest.Manifests}}{{.Platform.Architecture}} {{end}}' 2>/dev/null)
count=$(printf '%s\n' $platforms | grep -vc '^unknown$')
if [ "${count:-0}" -lt 2 ]; then
  echo "$TARGET:$TAG carries $count architecture(s) ($platforms); the source is an index of two" >&2
  exit 1
fi

echo "$TARGET:$TAG and :latest are $target_digest, $count architectures — the same image as $SOURCE:$TAG"
