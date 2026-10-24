# FT8CN_wty（BG7ZJW改版）

Developed by BG7YOZ and hosted by N0BOY

Run FT8 natively on Android

## 解码方案评估结论

测试条件：vivo V2338A（骁龙 8 Gen 3），60 个 15 秒 wav 录音、1298 条参考报文，同一份测试集、同一套计时口径。

### 结论一：除稳定性维护外，wsjtx 方案在解码方面不如 63e9d229

曾尝试用 mfsk-core（WSJT-X 算法）做频谱分段多核解码，对段数、SIC 轮数、OSD 开关、profile 做了梯度实验，结果如下：

| 方案                         | 召回率 | 平均耗时 | 线程 | 单位 CPU 时间召回率 |
| ---------------------------- | -----: | -------: | ---: | ------------------: |
| FT8CN 原快速解码（63e9d229） |  72.4% |  64.2 ms |    1 |            **1.13** |
| wsjtx 2 段 + 关闭 OSD        |  64.1% |  45.9 ms |    2 |                0.70 |
| wsjtx 3 段 + 关闭 OSD        |  66.2% |  46.7 ms |    3 |                0.47 |
| wsjtx 6 段 + 单遍 + OSD      |  72.3% |  83.8 ms |    6 |                0.14 |
| wsjtx 7 段 + 单遍 + OSD      |  72.7% |  97.3 ms |    7 |                0.11 |

- **召回率没有实质提升**：最好也只有 72.7%，与原快速解码的 72.4% 基本持平；
- **CPU 效率明显更低**：多核只是把耗时摊到更多核心上，最好的分段配置也只有原快速解码的 62%；
- **代码与包体代价大**：多一套 native 库、JNI、分段线程和结构化字段适配，收益却接近于零。

因此 wsjtx 解码挡位已从主线移除，仅以 tag 保留研究过程（`X_APP_wsjtx_1023`、`X_wsjtx-fast-sic-70`、`wsjtx-research-branch`）。主线只保留与解码无关的稳定性维护：长跑线程防重入、原生解码器释放、页面栈与视图泄漏修复、消息上限、发射前等待解码。

# 结论二：**单位解码时间的召回率，FT8CN 是我见过最优秀的**

以“召回率 ÷ 平均解码时 间”衡量（单线程，单位 %/ms）：

```text
FT8CN 原快速解码：72.4% / 64.2 ms = 1.13
wsjtx 最佳分段配置：66.2% / 46.7 ms / 3 线程 = 0.47
```

也就是说，FT8CN 用一个核心、约 64 ms 就达到 72.4% 召回；而 wsjtx 分段方案用三个核心、约 47 ms 也只有 66.2%。在单位解码时间的召回率这一指标上，FT8CN 是我见过最优秀的实现。

## 稳定性修改

- **解码线程防重入**：上一时隙解码尚未结束时直接跳过本时隙，避免长跑时解码线程和原生解码器不断堆积。
- **原生解码器必释放**：`DeleteDecoder` 移入 `finally`，即使解码过程抛异常也不会泄漏 native 内存。
- **后台任务收敛**：QTH 归属地查询、波形发送由 `CachedThreadPool` 改为单线程池；上一次 QTH 查询未完成时不再排队，只处理最新一批消息。
- **页面栈与视图泄漏修复**：底部导航改用 `setLaunchSingleTop` + `setPopUpTo(起始页)`，各 Fragment 在 `onDestroyView` 中释放 binding、Bitmap、WebView，Handler 对空 binding 做保护。100 轮切页后 Java 堆约 15 MB、Views 247，无崩溃。
- **消息列表并发安全**：RecyclerView 只读 UI 线程快照，解码线程并发增删消息时不再出现 `IndexOutOfBoundsException`，复制失败时保留上一份快照等下次刷新。
- **发射前等待解码**：自动发射前最多等本时隙解码 2 秒，超时则放弃本次发射，避免用上一轮的旧消息提前发射。
- **运行状态日志**：每分钟输出一次 Java 堆、线程数、消息数、呼叫数，便于长跑定位问题。

## UI 修改

- **移除“精简消息模式”**：删除对应的列表分支、布局 `call_list_holder_simple_item.xml` 和配置项，只保留一种列表样式，避免切换模式时崩溃。
- **消息列表上限 100 条**：超出后删除最旧的消息，长时间守听不会无限占用内存。
- **底部导航不再无限叠栈**：任意时刻只保留“起始页 + 当前页”，返回行为符合预期，同时避免重复创建 Fragment。

---

# 下面是原有的

```
免责声明：
   FT8CN旨在研究的目的，学习如何对FT8信号进行解码、发射等操作，不对使用者操作本APP所产生的后果负责。
   在中华人民共和国境内，使用FT8CN请遵守《中华人民共和国无线电管理条例》等相关规定。
   考虑到手机的性能和续航的限制，对信号的处理采用轻量化的运算，未做深度解码等处理。
   如有好的建议或问题可以提交到到”有问题要吐槽“。

Disclaimer
FT8CN aims to learn how to decode, transmit FT8 signal for research purposes, which is not responsible for the consequences caused by the user's operation.
Please comply with local laws and regulations when using FT8CN.
Considering the performance and endurance limitations of the mobile phone, the processing of the signal adopts lightweight operations instead of deep decoding and other processing.
Please click "FAQ" if you have good suggestions or questions .

BG7YOZ
2022-07-01

致敬：
   Steve Franke(K9AN)、Bill Somerville(G4WJS)、Joe Taylor(K1JT)，提出FT8和FT4协议（FT是Franke和Taylor的首字母），并在论文《The FT4 and FT8 Communication Protocols》详细介绍了FT4和FT8的设计初衷和在WSJT-X中的具体实现细节，成为完成本APP的根本指南。
   Karlis Goba(YL3JG)在代码的具体实现上提供了参考。
鸣谢：
   BG7YOY，在FT8CN开发阶段为我在无线电基本理论上作出指导，并为FT8CN设计了图标
   BG4IGX，在我刚刚入门业余无线电时为我在具体实践上作出指导。抖音上您可以搜到很多他的教学视频
   BD7MXN，帮助我对部分电台的连接控制做了一些测试，并提出改进建议
   BH2RSJ，帮助我建立了一个FT8CN测试群，为测试和后续改进提出了很多宝贵意见
   BH7ACO，帮助解决了某电台的驱动和相关的配置参数
   BG7IKK，帮助解决了只支持通过RTS控制PTT发射的电台的测试
   BI1NIZ，帮助注册账号，用于收集问题反馈和FAQ的功能
   BD3OOX以及石家庄业余无线电俱乐部，FT8CN的呼号地区归属数据提取至JTDX石家庄版，使呼号定位可以精确到中国的省级
   VR2UPU(BD7MJO)，在FT8的开发和使用经验上提供指导，并在多语言方面给予帮助
   BA2BI，在业余无线电的基础知识和通联的日志处理方面上给予帮助和指导
   BI3QXJ，在对某品牌系列电台的指令集上给予专业性的指导
   BG6TQD，在对某型号电台的指令集测试上给予帮助
   BG5CSS，提供某型号电台用于测试
   BG7YXN，提供某型号电台用于测试
   BG7YRB，对呼号规则运算提供帮助
   BG8KAH，提供设备用于测试
   BA7LVG、JE6WUD，完成日文的翻译校对工作
   BG6RI，帮助解决日志的信号报告问题
   SV1EEX，完成希腊文、西班牙文UI的翻译工作
   VR2VRC，帮助修正历史呼号读取规则
   BA7NQ，提供设备用于测试
   BD7MYM，对某型号的电台测试给予指导
   NØBOY，帮助提供Github源，以及翻译工作
   BG5JNT，帮助修正非标准呼号的识别问题
   BH3NEK，协助对某型号电台进行测试
   BG2ALB，协助对某型号电台进行测试
   BG6DRU，协助对某型号电台进行测试
   BG7NQF，提供某型号电台的隐藏指令，对一些设备做兼容性测试
   BH2VSQ，协助对某型号电台进行测试
   BG7YBW，协助对部分功能进行测试
   BH1RNN，协助对部分功能进行测试
   BG7BSM，协助对一些BUG进行调试
   BH4FTI，发现并协助对一些BUG进行调试
   BG8BXM（M哥），为FT8CN的使用做推广，抖音和B站上有很多他的教学视频
   BA7MFQ，为FT8CN的使用做推广，帮助测试
   BG2EFX，提供大数据量的日志用于测试
   DS1UFX，贡献(tr)uSDX audio over CAT代码
   BG8HT，提供某型号电台进行测试
   UB6LUM，帮助解决某型号电台的操作模式设置
   BG5VLI，贡献向Cloudlog和QRZ自动上传日志的代码
```

