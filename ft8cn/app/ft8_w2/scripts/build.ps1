# 构建 ft8_w2 的 libft8.so（arm64-v8a + x86_64，android-23）
#
# 依赖：
#   - Android NDK r27+（默认自动查找 28.2.13676358 / 27.2.12479018）
#   - CMake 3.22+（优先用 Android SDK 自带 cmake\3.22.1）
#   - Ninja
#   - ft8_w 原生源码（默认 D:\Desktop\ft8_w\native）
#
# 用法（PowerShell）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 -Ft8Root D:\src\ft8_w\native
#
# 产物：<包目录>\jniLibs\<abi>\libft8.so

param(
    [string]$Ft8Root = "D:\Desktop\ft8_w\native",
    [string]$Out = (Split-Path -Parent $PSScriptRoot),
    [string]$Ndk = $env:ANDROID_NDK_HOME,
    [string]$Api = "android-23",
    [string]$Cmake = ""
)

$ErrorActionPreference = "Stop"

# ---------- 定位 NDK ----------
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

$bin = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin"
$strip = Join-Path $bin "llvm-strip.exe"
$nm = Join-Path $bin "llvm-nm.exe"
foreach ($tool in @($strip, $nm)) {
    if (-not (Test-Path $tool)) { throw "NDK 工具缺失: $tool" }
}

if (-not (Test-Path (Join-Path $Ft8Root "CMakeLists.txt"))) {
    throw "Ft8Root 路径不对（缺少 CMakeLists.txt）: $Ft8Root"
}

# ---------- 定位 CMake / Ninja ----------
if (-not $Cmake) {
    $cmakeCandidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe",
        "$env:ANDROID_SDK_ROOT\cmake\3.22.1\bin\cmake.exe",
        "$env:ANDROID_HOME\cmake\3.22.1\bin\cmake.exe"
    )
    foreach ($c in $cmakeCandidates) {
        if (Test-Path $c) { $Cmake = $c; break }
    }
}
if (-not $Cmake) {
    $cmd = Get-Command cmake -ErrorAction SilentlyContinue
    if ($cmd) { $Cmake = $cmd.Source } else { throw "找不到 CMake。请安装或传 -Cmake <path>。" }
}

$ninja = "ninja"
if (Test-Path $Cmake) {
    $candidate = Join-Path (Split-Path -Parent $Cmake) "ninja.exe"
    if (Test-Path $candidate) { $ninja = $candidate }
}
if (-not (Get-Command $ninja -ErrorAction SilentlyContinue) -and -not (Test-Path $ninja)) {
    throw "找不到 Ninja。请安装或加入 PATH。"
}

Write-Host "NDK   : $Ndk"
Write-Host "CMake : $Cmake"
Write-Host "Ninja : $ninja"
Write-Host "API   : $Api"
Write-Host "源码  : $Ft8Root"
Write-Host "输出  : $Out"

$toolchain = Join-Path $Ndk "build\cmake\android.toolchain.cmake"
if (-not (Test-Path $toolchain)) { throw "NDK 工具链文件缺失: $toolchain" }

foreach ($abi in @("arm64-v8a", "x86_64")) {
    $build = Join-Path $Ft8Root "build-ft8w2-$abi"
    Write-Host "==> $abi : configure" -ForegroundColor Cyan
    & $Cmake -S $Ft8Root -B $build -G Ninja `
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
        "-DANDROID_ABI=$abi" `
        "-DANDROID_PLATFORM=$Api" `
        "-DCMAKE_BUILD_TYPE=Release"
    if ($LASTEXITCODE -ne 0) { throw "$abi 配置失败 (exit $LASTEXITCODE)" }

    Write-Host "==> $abi : build" -ForegroundColor Cyan
    & $ninja -C $build ft8
    if ($LASTEXITCODE -ne 0) { throw "$abi 构建失败 (exit $LASTEXITCODE)" }

    $so = Join-Path $build "libft8.so"
    if (-not (Test-Path $so)) { throw "$abi 产物不存在: $so" }

    $dstDir = Join-Path $Out "jniLibs\$abi"
    New-Item -ItemType Directory -Force -Path $dstDir | Out-Null
    $dst = Join-Path $dstDir "libft8.so"
    Copy-Item $so $dst -Force

    & $strip --strip-unneeded $dst
    if ($LASTEXITCODE -ne 0) { throw "$abi strip 失败" }

    $exports = (& $nm -D --defined-only $dst | Select-String "Java_com_ft8_nativecore").Count
    $size = [math]::Round((Get-Item $dst).Length / 1KB, 1)
    Write-Host ("    {0}  {1} KB  JNI exports={2}" -f $dst, $size, $exports) -ForegroundColor Green
    if ($exports -lt 14) { throw "$abi 的 JNI 导出不完整（$exports < 14）" }
}

Write-Host "完成。输出目录: $Out\jniLibs" -ForegroundColor Green
exit 0
