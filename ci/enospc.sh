#!/usr/bin/env bash
# A volume that runs out of space at a known moment, and the tests that need one (M-264).
#
# `SIGKILL` is covered: the crash tests kill a writing JVM and demand that everything the log
# admitted to still reads back. `ENOSPC` is a different failure and in practice a more common one,
# and until this file existed the write order was proved for "the process was killed" and not at all
# for "the write came back short".
#
#   ./ci/enospc.sh
#
# **Needs root, and that is why it is here rather than in `./gradlew check`.** A filesystem that
# ends is a loopback image with a real `mkfs` and a real `mount`; nothing rootless produces the
# condition honestly. tmpfs is not an option either, and not only for the reason the rest of this
# repository refuses it — a tmpfs full of pages is a machine out of memory, not a disk out of space.
set -uo pipefail

# Thirty-two mebibytes, and the number moved up once the tests stopped being one. Eight left about
# three and a half usable, which is plenty for any single test and not enough for three sharing the
# volume: the one needing four hundred small objects took it all, and its neighbours then failed
# creating a directory — a full disk that was the first test's doing rather than the stand's.
#
# Each test cleans up after itself for the same reason. Both halves are needed: room, so a test can
# work, and cleanup, so it does not spend the room permanently.
readonly IMAGE_MB=${BOCHKA_ENOSPC_MB:-32}

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
mount_point="$work/mnt"
ran=no

# Unmounting before the removal, and both in the trap. A `rm -rf` over a still-mounted image deletes
# into the image rather than the directory, and the loop device stays attached with nothing pointing
# at it — after a few runs the machine is out of loop devices and the failure names none of this.
cleanup() {
  local status=$?
  if mountpoint -q "$mount_point" 2>/dev/null; then
    sudo umount "$mount_point" 2>/dev/null
  fi
  rm -rf "$work" 2>/dev/null
  if [ "$ran" = no ]; then
    echo "this run never reached the tests, which is a failure rather than a pass" >&2
    [ "$status" -eq 0 ] && status=1
  fi
  exit "$status"
}
trap cleanup EXIT

command -v mkfs.ext4 >/dev/null || { echo "mkfs.ext4 is not installed" >&2; exit 3; }
sudo -n true 2>/dev/null || { echo "this needs passwordless sudo to mount a loopback image" >&2; exit 3; }

echo "making a ${IMAGE_MB} MiB volume that ends"
dd if=/dev/zero of="$work/volume.img" bs=1M count="$IMAGE_MB" status=none
mkfs.ext4 -q "$work/volume.img"
mkdir -p "$mount_point"
sudo mount -o loop "$work/volume.img" "$mount_point" || { echo "could not mount the image" >&2; exit 3; }
# The mount belongs to root; the JVM under test does not.
sudo chmod 777 "$mount_point"

df -h "$mount_point" | tail -1
echo

# `--rerun-tasks`, because the directory is new on every run while the test's own inputs are not:
# Gradle would call the task up to date and the stand would report a pass having executed nothing.
ran=yes
BOCHKA_ENOSPC_DIR="$mount_point" "$root/gradlew" -p "$root" --console=plain --rerun-tasks \
  :bochka-core:test :bochka-app:test --tests '*Enospc*'
status=$?

# A green suite is not evidence that anything was exercised here. The test returns on its first line
# when `BOCHKA_ENOSPC_DIR` does not reach the forked JVM, and that produces the same `tests=1`, the
# same exit code and — because filling three mebibytes takes milliseconds — the same duration as a
# real run. So the test leaves a marker on the volume itself, and this refuses a run without it.
classes=$(find "$root"/bochka-*/src/test -name 'Enospc*Test.kt' | wc -l | tr -d ' ')
markers=$(find "$mount_point/exercised" -type f 2>/dev/null | wc -l | tr -d ' ')
if [ $status -eq 0 ] && [ "$markers" -ne "$classes" ]; then
  echo "$markers of $classes ENOSPC tests reached the volume; the rest returned without touching it" >&2
  status=1
fi

# Printed, not just counted. The markers are the only account of what each test actually met on the
# volume, and they die with the mount a few lines below — a CI log that says "$classes of $classes"
# and nothing else cannot be read afterwards to tell a real run from a coincidence.
if [ -d "$mount_point/exercised" ]; then
  echo
  for marker in "$mount_point/exercised"/*; do
    [ -f "$marker" ] && printf '%s: %s\n' "$(basename "$marker")" "$(cat "$marker")"
  done
fi
exit $status
