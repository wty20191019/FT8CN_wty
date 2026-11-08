# 许可与来源说明

本目录包含以下组件：

## 1. libwsjtx_Android.so

- 许可：**GPL-3.0-or-later**
- 内容：`jni/wsjtx_jni.c` + 静态链接的 mfsk-ffi（mfsk-core）
- 源码：
  - 本目录 `jni/wsjtx_jni.c`
  - mfsk-core：https://github.com/jl1nie/mfsk-core
  - 构建脚本：`scripts/build.ps1`

## 2. WsjtxDecoder.java

- 许可：**GPL-3.0-or-later**
- 源码：本目录 `java/com/bg7yoz/ft8cn/wsjtx/WsjtxDecoder.java`

## 3. mfsk-core / mfsk-ffi

- 许可：**GPL-3.0-or-later**
- 上游：https://github.com/jl1nie/mfsk-core
- 版本：0.12.0（本目录构建时）
- 说明：算法源自 WSJT-X（Joe Taylor K1JT 等），由 mfsk-core 用 Rust 重写。
  mfsk-core 不是 WSJT-X 官方项目。

## 4. FT8CN

- 许可：**MIT**
- 上游：https://github.com/N0BOY/FT8CN
- 本目录不修改 FT8CN 原有代码；只提供可复制进去的 `.so` 和 Java 适配类。

## 分发注意事项

`libwsjtx_Android.so` 是 GPL-3.0-or-later 代码。把本库与 FT8CN 一起打包成
APK 分发时，整体分发需要满足 GPL-3.0 的要求，包括但不限于：

- 提供对应的完整源码（至少包括 `wsjtx_jni.c`、mfsk-core/mfsk-ffi 源码、
  构建脚本，以及 FT8CN 侧为接入而修改的源码）；
- 保留版权和许可声明；
- 不以作者名义背书。

如果只是自己本地编译、自己使用，不对外分发，则不受 GPL 的分发条款约束。

## 商标

“WSJT-X”是 WSJT Development Team 的商标。本目录不是 WSJT-X 官方发布，
不应以 WSJT-X 官方名义分发。
