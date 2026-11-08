# NOTICE / 第三方组件声明

`ft8_w2` 是 `ft8_w` 工程 FT8 C 编解码核心的 Android 交付包。

## ft8_lib

- 项目：https://github.com/kgoba/ft8_lib
- 作者：Kārlis Goba (YL3JG)
- 许可证：MIT License
- 本包的常量表、CRC、LDPC 译码、消息打包/解包、文本工具、
  STFT 监视器以及 kissfft 复用了 ft8_lib 的代码，并做了封装与扩展
  （公共 API、多线程、会话接口、JNI 桥、信号消除、OSD 等）。

```
MIT License

Copyright (c) 2018 Kārlis Goba

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## kissfft（经 ft8_lib 内联引入）

- 作者：Mark Borgerding
- 许可证：BSD-3-Clause（见源码头注释）

## 关于 WSJT-X / JTDX

算法思路参考 WSJT-X / JTDX；JTDX 为 GPLv3 项目，仅作算法参考，
其代码未被复制进本包。
