#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNTIME_LIB_DIR="${RUNTIME_LIB_DIR:-$ROOT_DIR/tools/model_compile/qairt/runtime-libs/ubuntu-22.04}"
DEB_DIR="$RUNTIME_LIB_DIR/debs"
ROOTFS_DIR="$RUNTIME_LIB_DIR/root"

mkdir -p "$DEB_DIR" "$ROOTFS_DIR"

(
  cd "$DEB_DIR"
  apt-get download libc++1-14 libc++abi1-14 libomp5-14 libunwind-14
)

for deb in "$DEB_DIR"/*.deb; do
  dpkg-deb -x "$deb" "$ROOTFS_DIR"
done

found_libcxx="$(find "$ROOTFS_DIR" -name 'libc++.so.1' -type f -o -name 'libc++.so.1' -type l | head -n 1 || true)"
if [[ -z "$found_libcxx" ]]; then
  echo "Failed to extract libc++.so.1 into $ROOTFS_DIR" >&2
  exit 2
fi

find "$ROOTFS_DIR" \
  \( -name 'libc++.so*' -o -name 'libc++abi.so*' -o -name 'libomp.so*' \) \
  -print | sort
