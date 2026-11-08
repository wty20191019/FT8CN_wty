# ft8_w2 — FT8CN 的 ft8_w 解码原生库

本目录是给 FT8CN 用的 **FT8 解码**交付包，与 `F_mfsk-core` 并列：

- `libft8.so`：ft8_w C 核心 + JNI 桥（arm64-v8a / x86_64）
- `Ft8Native.java`：JNI 绑定（包名 `com.ft8.nativecore`）
- `Ft8WDecoder.java`：FT8CN 适配层（`precise` 档，可直接返回结构化解码行）
- `jni/ft8_jni.cpp`：JNI 源码（与 C 核心一起重新编译时使用）
- `scripts/build.ps1`：重新构建 `libft8.so` 的脚本
- `移植手册.md`：从零接入 FT8CN 的完整步骤

## 快速开始

1. 把 `jniLibs/arm64-v8a/libft8.so`、`jniLibs/x86_64/libft8.so`
   复制到 `ft8cn/app/libs/<abi>/`。
2. 把 `java/com/ft8/nativecore/Ft8Native.java` 与
   `java/com/bg7yoz/ft8cn/ft8w/Ft8WDecoder.java` 复制到
   `ft8cn/app/src/main/java/` 对应包路径。
3. 按 `移植手册.md` 第 3 步修改 `GeneralVariables`、`FT8SignalListener`、
   `ConfigFragment`（以及布局/字符串资源）。
4. `gradlew assembleDebug`。

## 默认档位（precise）

```text
fmin=200  fmax=3000  sample_rate=12000
time_osr=2  freq_osr=2  max_candidates=100
min_sync_score=10  ldpc_iterations=20
decode_depth=0  osd_depth=3  snr_mode=2
enable_subtract=false  num_threads=1
```

参考：全量 1298 条，命中 953、召回 73.4%，avg 14.2 ms、P95 15.6 ms
（宿主 clang，`--repeat 6`）。

## 版本

- ft8_w2：0.1.0
- ft8 C core：0.1.0
- 目标：Android `arm64-v8a`、`x86_64`
- 最小 API：23（与 FT8CN `minSdk` 一致）

## 许可

基于 ft8_lib（MIT, © 2018 Kārlis Goba）；详见 `LICENSE-NOTICE.md`。
