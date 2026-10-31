#!/usr/bin/env bash
# 下载 sherpa-onnx 预编译 AAR 到 app/libs/（Linux / macOS / CI）
set -euo pipefail

VERSION="${SHERPA_ONNX_VERSION:-2.0.1}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT/app/libs"
DEST="$DEST_DIR/sherpa-onnx-$VERSION.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-$VERSION.aar"

mkdir -p "$DEST_DIR"
if [ -f "$DEST" ]; then
  echo "已存在: $DEST ($(du -h "$DEST" | cut -f1))"
  exit 0
fi

echo "下载 $URL ..."
curl -fL --retry 3 -o "$DEST" "$URL"

SIZE=$(wc -c < "$DEST")
echo "完成: $DEST ($((SIZE/1024/1024)) MB)"
if [ "$SIZE" -lt 10485760 ]; then
  echo "文件大小异常，下载可能失败" >&2
  exit 1
fi
