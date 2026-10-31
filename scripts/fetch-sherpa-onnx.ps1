# Windows: 下载 sherpa-onnx 预编译 AAR 到 app/libs/
# 用法: powershell -ExecutionPolicy Bypass -File scripts/fetch-sherpa-onnx.ps1
$ErrorActionPreference = 'Stop'

$version = '2.0.1'
$url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar"
$root = Split-Path -Parent $PSScriptRoot
$destDir = Join-Path $root 'app/libs'
$dest = Join-Path $destDir "sherpa-onnx-$version.aar"

New-Item -ItemType Directory -Force -Path $destDir | Out-Null

if (Test-Path $dest) {
    Write-Host "已存在: $dest ($([math]::Round((Get-Item $dest).Length/1MB,1)) MB)"
    exit 0
}

Write-Host "下载 $url ..."
# 国内网络慢时可改用镜像，例如:
#   $url = "https://ghproxy.net/$url"
$ProgressPreference = 'SilentlyContinue'
Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing

$size = (Get-Item $dest).Length
Write-Host "完成: $dest ($([math]::Round($size/1MB,1)) MB)"
if ($size -lt 10MB) {
    throw "文件大小异常（$size 字节），可能下载失败，请检查网络或镜像地址。"
}
