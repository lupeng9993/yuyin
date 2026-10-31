# 本地语音助手（Android）

本地优先的语音助手：唤醒 → VAD 截句 → 离线识别 → 本地规则优先 → 云端大模型增强（可选）→ 意图执行 → 语音播报。

- 语言 / UI：Kotlin + Jetpack Compose
- 本地语音：sherpa-onnx（唤醒 KWS / ASR / TTS 一套推理库）；VAD 用 Silero 模型（可选）+ 能量阈值兜底
- 云端：可插拔适配器（豆包、通义千问、OpenAI 兼容、私有）
- 执行：无障碍服务（微信发送、应用启动、音量、截屏、媒体控制）
- 构建：GitHub Actions，先跑单元测试再出 debug + 签名 release

## 三条硬约束

1. **不上传原始音频**：云端只发送识别后的文本。
2. **不内置任何模型 / 密钥**：全部由用户导入或下载，密钥用 Android Keystore 加密。
3. **四种动作、不扩展**：`navigate` / `send_wechat` / `other` / `unknown`，超出范围一律 `unknown`。

## 快速开始

```bash
# 1. 取推理库（约 49 MB，仓库不提交）
bash scripts/fetch-sherpa-onnx.sh        # Linux / macOS
powershell -ExecutionPolicy Bypass -File scripts\fetch-sherpa-onnx.ps1   # Windows

# 2. 构建
gradle :app:assembleDebug
```

没有本地工具链也没关系：推到 GitHub，Actions 会自动出包（见 [docs/BUILD.md](docs/BUILD.md)）。

## 首次使用

1. 装 APK，授予**录音**和**通知**权限。
2. 在设置里导入或下载三个模型：唤醒（KWS）、识别（ASR）、合成（TTS）。
3. 打开「启用唤醒」，点「开始监听」。
4. 需要发微信 / 开应用 / 截图，再去系统设置手动开启无障碍服务。

模型从哪来、目录怎么放：[docs/MODELS.md](docs/MODELS.md)
密钥怎么申请：[docs/KEYS.md](docs/KEYS.md)
权限怎么开：[docs/PERMISSIONS.md](docs/PERMISSIONS.md)
意图协议：[docs/PROTOCOL.md](docs/PROTOCOL.md)

## 执行链路

```
常驻前台服务 + 悬浮小球
   └─ AudioRecord 16 kHz 单声道 PCM
        └─ 唤醒词检测（sherpa-onnx KWS，关键词为纯文本）
             └─ 命中 → VAD 截句（能量 VAD，静音 600 ms 结束）
                  └─ 离线 ASR → 文本
                       ├─ 本地规则命中 → 直接执行
                       └─ 未命中 + 云端开 → 大模型 → 严格 JSON 校验 → 执行
                            └─ 失败 → 按「自动降级」再试本地规则
                                 └─ 执行器（导航 / 微信 / 系统控制）+ 二次确认
                                      └─ TTS 播报
```

## 目录

```
app/src/main/java/com/voiceassistant/
├─ nlu/        意图协议、严格 JSON 解析、本地规则、固定提示词
├─ cloud/      可插拔适配器、HTTP 层、错误归类、降级编排
├─ exec/       调度器、导航 / 微信 / 系统执行器、无障碍网关
├─ service/    前台服务、音频采集、VAD、悬浮窗、通知、开机自启
├─ service/engine/  唤醒 / 识别 / 播报的引擎抽象与装配
├─ data/       DataStore 配置、Keystore 加密存储
├─ model/      模型目录管理
├─ ui/         Compose 界面
└─ util/       系统设置跳转
```

## 已知边界

- **开机自启**：Android 14+ 只允许「已授予悬浮窗权限」等少数情况后台启动麦克风前台服务；本项目已做开关 + 静默兜底，但国产 ROM 仍可能拦截。
- **微信自动化**：无障碍遍历依赖微信界面结构，微信改版即可能失效；找不到节点时只报失败，绝不盲点。
- **截屏**：需要 Android 11+ 且无障碍已开启。
- **高德 Key**：客户端直连 Web API，配额与泄露风险由用户承担。
- 未实现（按文档排除）：蓝牙、ESP32、串口、智能家居、自建后端。
