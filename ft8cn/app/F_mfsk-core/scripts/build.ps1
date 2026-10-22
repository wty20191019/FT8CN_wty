# SPDX-License-Identifier: GPL-3.0-or-later
#
# 构建 libwsjtx_Android.so（arm64-v8a + x86_64）
#
# 依赖：
#   - Rust 1.98+，rustup target add aarch64-linux-android x86_64-linux-android
#   - cargo-ndk（cargo install cargo-ndk）
#   - Android NDK r28（或 r27+）
#   - mfsk-core 源码（默认 D:\Desktop\mfsk-core）
#
# 用法（PowerShell）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 -MfskCore D:\src\mfsk-core
#
# 注意：mfsk-ffi 目前必须用 --features mobile 构建。
# 只用 --features protocols 会因为源码里无条件引用 q65/wspr/jt9/jt65
# 而编译失败（Cargo.toml 里有说明）。mobile = protocols + legacy + jtty，
# 不启用 rayon/serde，适合 Android。

param(
    [string]$MfskCore = "D:\Desktop\mfsk-core",
    [string]$Out = (Split-Path -Parent $PSScriptRoot),
    [string]$Ndk = $env:ANDROID_NDK_HOME
)

$ErrorActionPreference = "Stop"

if (-not $Ndk) {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\ndk\28.2.13676358",
        "$env:LOCALAPPDATA\Android\Sdk\ndk\27.2.12479018"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { $Ndk = $c; break }
    }
}
if (-not $Ndk -or -not (Test-Path $Ndk)) {
    throw "找不到 Android NDK。请设置 ANDROID_NDK_HOME。"
}

$env:ANDROID_NDK_HOME = $Ndk
$bin = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin"
$clang = Join-Path $bin "clang.exe"
$strip = Join-Path $bin "llvm-strip.exe"
$readelf = Join-Path $bin "llvm-readelf.exe"
$nm = Join-Path $bin "llvm-nm.exe"

foreach ($tool in @($clang, $strip, $readelf, $nm)) {
    if (-not (Test-Path $tool)) { throw "NDK 工具缺失: $tool" }
}
if (-not (Test-Path (Join-Path $MfskCore "Cargo.toml"))) {
    throw "MfskCore 路径不对: $MfskCore"
}

$include = Join-Path $Out "include"
$jni = Join-Path $Out "jni\wsjtx_jni.c"
$jniLibs = Join-Path $Out "jniLibs"
New-Item -ItemType Directory -Force -Path $include, $jniLibs | Out-Null

# 头文件由 mfsk-ffi 的 build.rs 生成；这里复制一份，保证 JNI 编译
# 使用的是与静态库同一版本的 ABI。
Copy-Item (Join-Path $MfskCore "mfsk-ffi\include\mfsk.h") (Join-Path $include "mfsk.h") -Force

Write-Host "==> cargo ndk build (mfsk-ffi, mobile)" -ForegroundColor Cyan
Push-Location $MfskCore
try {
    cargo ndk -t arm64-v8a -t x86_64 build -p mfsk-ffi --release --no-default-features --features mobile
    if ($LASTEXITCODE -ne 0) { throw "cargo ndk build 失败 (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

$jobs = @(
    @{ Abi = "arm64-v8a"; Target = "aarch64-linux-android"; Triple = "aarch64-linux-android"; AndroidTarget = "aarch64-linux-android23" },
    @{ Abi = "x86_64";    Target = "x86_64-linux-android";  Triple = "x86_64-linux-android";  AndroidTarget = "x86_64-linux-android23" }
)

foreach ($job in $jobs) {
    $abiDir = Join-Path $jniLibs $job.Abi
    New-Item -ItemType Directory -Force -Path $abiDir | Out-Null
    $staticLib = Join-Path $MfskCore "target\$($job.Triple)\release\libmfsk.a"
    if (-not (Test-Path $staticLib)) {
        throw "静态库不存在: $staticLib"
    }
    $so = Join-Path $abiDir "libwsjtx_Android.so"

    Write-Host "==> $($job.Abi): compile + link" -ForegroundColor Cyan
    & $clang "--target=$($job.AndroidTarget)" -shared -fPIC -O2 -Wall -Wextra `
        "-I$include" -o $so $jni $staticLib `
        -llog -lm -ldl `
        "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections" "-Wl,--exclude-libs,ALL"
    if ($LASTEXITCODE -ne 0) { throw "$($job.Abi) 链接失败 (exit $LASTEXITCODE)" }

    & $strip --strip-unneeded $so
    if ($LASTEXITCODE -ne 0) { throw "strip 失败: $so" }

    $size = [math]::Round((Get-Item $so).Length / 1MB, 2)
    Write-Host "    $so (${size} MB)" -ForegroundColor Green

    $machine = (& $readelf -h $so | Select-String "Machine:").Line.Trim()
    $load = (& $readelf -l $so | Select-String "LOAD" | Select-Object -First 1).Line.Trim()
    $exports = (& $nm -D --defined-only $so | Select-String "Java_com_bg7yoz_ft8cn_wsjtx").Count
    Write-Host "    $machine"
    Write-Host "    $load"
    Write-Host "    JNI exports: $exports"
    if ($exports -lt 5) { throw "$($job.Abi) 的 JNI 导出不完整" }
}

Write-Host "完成。输出目录: $jniLibs" -ForegroundColor Green
exit 0
