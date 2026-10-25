# F_mfsk-core — FT8CN 的 FT8 解码原生库

本目录是给 FT8CN 用的 **FT8 解码** 交付包：

- `libwsjtx_Android.so`：基于 mfsk-core（WSJT-X 算法的 Rust 实现）
- `WsjtxDecoder.java`：Java 调用入口
- `wsjtx_jni.c`：JNI 源码
- `mfsk.h`：mfsk-ffi C ABI
- `移植手册.md`：**从零接入 FT8CN 的完整步骤**
- `scripts/build.ps1`：重新构建脚本

## 快速开始

1. 把 `jniLibs/arm64-v8a/libwsjtx_Android.so` 复制到
   `ft8cn/app/libs/arm64-v8a/`。
2. 把 `java/com/bg7yoz/ft8cn/wsjtx/WsjtxDecoder.java` 复制到
   `ft8cn/app/src/main/java/com/bg7yoz/ft8cn/wsjtx/`。
3. 按 `移植手册.md` 第 3 步修改 `FT8SignalListener.decodeFt8()`。
4. `gradlew assembleDebug`。

详细说明、代码片段、排错和许可证信息都在 **`移植手册.md`**。

## 版本

- mfsk-core / mfsk-ffi：0.12.0
- 目标：Android arm64-v8a、x86_64
- 最小 API：23（FT8CN 的 minSdk 也是 23）
- 16 KB page size：已满足

## 许可

GPL-3.0-or-later。详见 `LICENSE-NOTICE.md`。
