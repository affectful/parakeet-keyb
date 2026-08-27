#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
AAR="$ROOT/libs/sherpa-onnx-1.13.6.aar"
EXPECTED="0012d9a28f15bd6fb966b62b70a75da3990512fdccce28b83098248ce4be1698"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.6/sherpa-onnx-1.13.6.aar"

mkdir -p "$ROOT/libs"

if [ -f "$AAR" ] && [ "$(shasum -a 256 "$AAR" | cut -d ' ' -f 1)" = "$EXPECTED" ]; then
  printf '%s\n' "sherpa-onnx AAR is already installed."
  exit 0
fi

printf '%s\n' "Downloading sherpa-onnx 1.13.6..."
curl -L --fail --retry 3 -o "$AAR.part" "$URL"

ACTUAL=$(shasum -a 256 "$AAR.part" | cut -d ' ' -f 1)
if [ "$ACTUAL" != "$EXPECTED" ]; then
  rm -f "$AAR.part"
  printf '%s\n' "Checksum mismatch for sherpa-onnx AAR." >&2
  exit 1
fi

mv "$AAR.part" "$AAR"
printf '%s\n' "Installed $AAR"
