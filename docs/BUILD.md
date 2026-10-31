# 构建说明

## 方式一：GitHub Actions（推荐，本机不需要装任何工具）

1. 把整个 `voiceassistant/` 目录推到 GitHub 仓库（公开仓库 Actions 额度更宽松）。
2. Actions 会自动运行 `.github/workflows/build-apk.yml`。
3. 在运行记录的 Artifacts 里下载：
   - `voiceassistant-debug` → 直接安装的调试包
   - `voiceassistant-release` → 未签名或已签名的发布包

工作流做的事：装 JDK 17 → 装 SDK 35 → 装 Gradle 8.9 → 下载 sherpa-onnx AAR →
（可选）解出签名材料 → `assembleDebug` + `assembleRelease` → 上传产物。

## 方式二：本机构建

前置：JDK 17、Android SDK（platform 35 + build-tools 35.0.0）、Gradle 8.9。

```bash
bash scripts/fetch-sherpa-onnx.sh
gradle :app:assembleDebug
```

Windows：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\fetch-sherpa-onnx.ps1
gradle :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 为什么仓库里没有 AAR 和模型

- `sherpa-onnx-2.0.1.aar` 约 49 MB，属于二进制依赖，由脚本/CI 在构建时拉取。
- 模型动辄上百 MB，且许可证各异，绝不入库、绝不打包进 APK。

`app/build.gradle` 里的 `checkSherpaAar` 任务会在构建前检查 AAR 是否存在，
缺失时给出「跑哪个脚本」的明确提示，而不是一句看不懂的 Gradle 报错。

## 签名发布包

### 本地

在仓库根目录建 `keystore.properties`（已在 `.gitignore` 里）：

```properties
storeFile=release.keystore
storePassword=***
keyAlias=voiceassistant
keyPassword=***
```

### CI

在仓库 Settings → Secrets 里配置：

| Secret | 含义 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.keystore` 的结果 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 别名 |
| `KEY_PASSWORD` | 别名密码 |

没配 `KEYSTORE_BASE64` 时，release 产出未签名包，**构建不会失败**。

## 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `缺少 sherpa-onnx-2.0.1.aar` | 先跑 `scripts/fetch-sherpa-onnx.*` |
| 找不到 SDK 35 | 装 `platforms;android-35`、`build-tools;35.0.0` |
| 只有 armeabi / x86 包 | 本项目只保留 `arm64-v8a`，模拟器请用 arm64 镜像 |
| Gradle 版本不符 | 用 8.9，AGP 8.7.3 需要 8.9+ |
