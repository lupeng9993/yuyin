# 交付状态与验证说明

## 已经做到的

- 工程可被 Gradle 解析：`settings.gradle` / `build.gradle` / `app/build.gradle` 齐全，CI 工作流完整。
- **sherpa-onnx v2.0.1 的 Kotlin API 逐行核对过**（子代理抓取官方 tag 源码），四个模块的类名、
  字段顺序、方法签名以官方源码为准，速查表见仓库根目录
  `sherpa-onnx-v2.0.1-android-kotlin-api.md`。
  - `acceptWaveform(samples, sampleRate)` — samples 在前
  - KWS 没有 `isDetected()`，用 `isReady / decode / getResult().keyword / reset`
  - `OfflineTts.sampleRate()` 是函数
  - `SileroVadModelConfig` 没有 `sampleRate`/`bufferSizeInSeconds`，采样率在 `VadModelConfig`
- 一次静态自查修掉了两个**必然编译失败**的问题：
  - 一处 `GroupCard(a, b, c) { } { }` 的双尾随 lambda（Kotlin 不允许）
  - 一处 `Surface(onClick=...)` 的 material3 实验 API 依赖，改成 `clickable` 组合
- Kotlin 版本提到 2.2.20：新版编译器能读旧 metadata，避免 AAR 若用新 Kotlin 编译导致
  "compiled with a newer Kotlin version" 直接失败；同时加了 `-Xskip-metadata-version-check` 兜底。
- 补了 **18 个 JVM 单元测试**（`app/src/test/`），覆盖本地规则匹配、多动作切分、协议清洗、
  越界截断、字段缺失降级、旧格式兼容。CI 会在出包前先跑 `testDebugUnitTest`——
  即使 APK 构建有问题，这部分逻辑错误也会先暴露出来。

## 还没验证的（必须说清楚）

**这份代码没有在本机编译过**：本机没有 JDK / Gradle / Android SDK，且命令执行在沙箱下直接
以 `0xC0000142` 退出。所以下面这些只能靠第一次 CI 构建来确认：

1. `flatDir` 引入 `sherpa-onnx-2.0.1.aar` 的解析是否正常
2. Compose BOM 2024.09.00 + Kotlin 2.2.20 + AGP 8.7.3 的组合是否无冲突
3. AGP 与 KGP 版本兼容性告警是否只是告警
4. 各处 Compose API（`Divider`、`DropdownMenuItem(text=...)`）在当前 material3 版本下的可用性

## 第一次 CI 红了怎么办

把 Actions 日志里 `> Task :app:compileDebugKotlin` 之后的报错行贴出来即可，通常只有几处；
按文件+行号修很快。

## 已知的功能级不确定

- **KWS 关键词行格式**：没拿到官方逐字示例。默认读取模型包自带的 `keywords.txt`，
  所以只要模型包里有现成关键词就能用；自定义唤醒词需要按 `docs/MODELS.md` 的格式改文件。
- **微信无障碍节点**：按通用 `contentDescription="搜索/发送"` + `isEditable` 匹配，
  没有真机日志，微信改版或深度定制 ROM 上可能找不到节点（会明确报失败，不会盲点）。
- **Silero VAD 模型**：需要自己导入 `silero_vad.onnx`；没有时自动用内置能量 VAD，功能不缺。
