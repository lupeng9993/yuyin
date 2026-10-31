# 密钥配置

原则：**所有密钥只存在用户手机上**，用 Android Keystore 的 AES-256-GCM 加密后写入 DataStore；
源码、APK、仓库里都没有任何密钥。

## 云端大模型

设置 → 云端解析（大模型）：

| 字段 | 说明 |
|---|---|
| 服务商 | 豆包 / 通义千问 / OpenAI 兼容 / 私有 |
| 基础地址 | 预置值可改，支持中转网关、局域网 `http://IP:11434/v1`（Ollama） |
| 模型名称 | 通义 `qwen-plus`；豆包填**推理接入点 ID**（`ep-` 开头） |
| API Key | 加密保存，可显示/隐藏 |

### 各平台怎么拿 Key

- **豆包（火山方舟）**：控制台创建「推理接入点」并绑定模型，地址用 `https://ark.cn-beijing.volces.com/api/v3`，模型名填 `ep-xxxx`。
- **通义千问（阿里云百炼）**：开通服务拿 API Key，地址用 `https://dashscope.aliyuncs.com/compatible-mode/v1`，模型用 `qwen-plus`。
- **书生·浦语（InternLM）**：登录 [internlm.intern-ai.org.cn](https://internlm.intern-ai.org.cn/) 生成 token，
  地址 `https://chat.intern-ai.org.cn/api/v1`，模型 `intern-latest`。
  该接口**不支持 `response_format`**，选中服务商时会自动关闭「启用强制 JSON」；限流每人每分钟 30 次，
  token 过期会返回 401。
- **OpenAI 兼容**：任何支持 `/v1/chat/completions` 的服务，包括 one-api / new-api 中转、vLLM、Ollama。
- **私有协议**：实现 `CloudAdapter` 并在 `CloudIntentService` 注册，默认会回退到兼容实现。

配好后点「测试连接」，成功会显示模型返回解析出的动作；失败会显示具体原因（401 密钥错、403 权限、429 限流、超时、无法解析）。

## 高德地图

设置 → 技能管理 → 导航：

1. 去 [高德开放平台](https://lbs.amap.com/) 注册并创建应用。
2. 添加 Key 时，**服务平台必须选「Web服务」**——选 Android平台拿到的 Key 调 REST 接口会返回 `USERKEY_PLAT_NOMATCH`(10009)。
3. 复制 **Key** 和 **安全密钥**（`jscode`）两个值，都填进本应用。
   - 2021-12-02 之后申请的 Key 必须带安全密钥，否则返回 `INVALID_USER_SCODE`(10008)。
   - 安全密钥与控制台里的 Key 在同一个页面，Key 列表里能看到。
4. 打开「启用导航技能」，点「测试地点解析（大明湖景区）」验证。

测试失败时弹窗会显示**高德原始错误码**，对照表：

| 错误码 | 含义 | 处理 |
|---|---|---|
| 10001 INVALID_USER_KEY | Key 无效 | 检查是否复制完整 |
| 10008 INVALID_USER_SCODE | 安全密钥缺失/不匹配 | 填 jscode |
| 10009 USERKEY_PLAT_NOMATCH | Key 平台类型错 | 重新建 Web服务 类型的 Key |
| 10003 / 10044 | 超出日配额 | 明天再试或提高配额 |
| INVALID_PARAMS | 参数错误 | 地址为空 |

> 客户端直连 Web API 的 Key 泄露与配额风险由用户自担（文档已明确排除自建后端）。
> 建议单独申请一个 Key 只给本应用用，并在控制台设置配额上限。

## 安全边界

- 音视频：**只上传识别后的文本**，任何情况下不上传音频。
- 存储：密钥加密、模型与日志只在应用私有目录。
- 备份：已关闭 `allowBackup`，防止密钥被系统备份带出。
- 日志导出：只包含运行日志，不含明文密钥（密钥在日志里一律打码/不写）。
