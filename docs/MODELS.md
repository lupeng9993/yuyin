# 模型说明

**仓库和 APK 里不含任何模型。** 三个模型都由你自己获取，放进应用私有目录。

## 三类模型

| 类别 | 用途 | 目录（应用私有） |
|---|---|---|
| KWS | 唤醒词检测（关键词是纯文本，改词不用重训） | `filesDir/models/kws/<模型名>/` |
| ASR | 离线语音转文字 | `filesDir/models/asr/<模型名>/` |
| VAD | 语音端点检测（可选，silero_vad.onnx） | `filesDir/models/vad/` |
| TTS | 离线语音合成 | `filesDir/models/tts/<模型名>/` |

设置页里「导入本地文件」会把文件复制到对应目录，并把路径写进配置。

## VAD（可选但推荐）

`sherpa-onnx` 的 `silero_vad.onnx`（约 2 MB）放进 `filesDir/models/vad/` 并在设置页导入后，
截句改用模型推理，嘈杂环境（空调、键盘、街道）下比能量阈值稳得多。

不导入也能用：内置能量 VAD 会自动接管，功能不缺，只是抗噪差一些。

## 推荐模型

### 唤醒（KWS）

- `sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01`（中文，体积小，适合常驻）
- 必需文件：`encoder*.onnx`、`decoder*.onnx`、`joiner*.onnx`、`tokens.txt`、`keywords.txt`

唤醒词就写在 `keywords.txt` 里，一行一个，格式（BPE 音素 + 显示文本）：

```
你 你 @你好小助手
```

**改唤醒词 = 改这个文件，不需要重新训练。** 应用里也做了对应入口：

- 首页状态卡会显示「可用唤醒词」，就是这个模型到底能唤醒哪些词
- 设置 → 唤醒与音频 → 模型支持的唤醒词：点一下即切换
- 还有「自定义关键词行（高级）」输入框，可直接粘贴一行原始关键词

如果「可用唤醒词」显示"未加载关键词"或报错，说明模型包里没有可用的 `keywords.txt`，
唤醒不会工作——换一个模型，或手动把关键词行写进去。

### 识别（ASR）

- `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`（中文效果最好，int8 约 230 MB）
- 必需文件：`model.int8.onnx`（或 `model.onnx`）、`tokens.txt`

### 合成（TTS）

- `vits-zh-hf-fanchen-C` / `vits-zh-hf-theresa`（22.05 kHz，音质明显好于 8 kHz 的 aishell3）
- `matcha-icefall-zh-baker`（22.05 kHz，另一个高保真选择）
- 老方案 `vits-zh-aishell3`（174 发音人，约 116 MB，**8 kHz 电话音质**，已降为可选）
- 必需文件：`model.onnx`、`tokens.txt`、`lexicon.txt`（按模型实际文件名动态识别，不写死）

## 目录里要有什么

`ModelRepository` 用「目录里有没有 `.onnx`」判断是否像模型，文件名不做硬编码；
具体加载时按模型元数据找 `model*.onnx` / `tokens.txt` / `lexicon.txt`。

## 获取方式

1. **应用内下载**（设置页按钮）：从 sherpa-onnx 官方 Releases 下载 tar.bz2 并解压到对应目录。
2. **本地导入**：自己下载好，用系统文件选择器导入单个文件。
3. **手动放置**：电脑上解压后整体推到 `filesDir/models/<kind>/<name>/`（需要 root 或 adb）。

下载源默认是 `https://github.com/k2-fsa/sherpa-onnx/releases`，国内网络不通时可在设置里改镜像地址。

## 校验

下载后对文件做大小与 MD5 校验，解压到 `filesDir/tts/<model-name>/` 之类的目录；
校验失败会提示重新下载，不会带着残缺模型启动。

## 许可证

模型许可证由各自发布方决定（多为 Apache-2.0 或 CC 系列），**使用前请自行确认**。
本项目不重新分发模型。
