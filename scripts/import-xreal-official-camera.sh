#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/ARLauncher.apk" >&2
  exit 2
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
apk="$1"
out="$root/app/vendorJniLibs/arm64-v8a"
mkdir -p "$out"

# The NR loader discovers plugins by filename. Import the complete NR runtime
# set instead of guessing a transitive subset; these files remain gitignored.
unzip -Z1 "$apk" | grep -E '^lib/arm64-v8a/(libXREALXRPlugin|libnr_.*|libxralog)\.so$' | while read -r entry; do
  unzip -p "$apk" "$entry" > "$out/${entry##*/}"
done

echo "Imported optional XREAL official camera runtime into: $out"
