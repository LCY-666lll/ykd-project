# 微信 Bot 图片能力闭环设计

日期：2026-07-18  
状态：已获方案批准，待用户完成百炼本地密钥配置后实施。

## 目标与范围

在不改变现有微信登录、轮询和 DeepSeek 文本对话主链路的前提下，增加：

- 基于环境变量的阿里云百炼接入；
- DeepSeek 结构化意图路由；
- 文生图与微信真实图片回传；
- 微信图片的视觉理解并以文字回复。

首版不实现数据库、会话持久化、多轮任务、图片长期存储、多个供应商切换或后台管理页面。

## 模型选择

| 能力 | 模型 | 调用目的 |
| --- | --- | --- |
| 意图识别、普通文本回复 | 既有 `deepseek-chat` | 输出严格 JSON 路由或直接回答。 |
| 生图 | 百炼 `qwen-image-2.0` | 接收用户描述，返回临时图片 URL。 |
| 看图/OCR | 百炼 `qwen3-vl-flash` | 接收微信图片字节（Base64）及用户问题，返回文本理解结果。 |

## 意图契约

DeepSeek 路由阶段仅允许输出如下 JSON，禁止输出 Markdown：

```json
{"intent":"TEXT","prompt":"用户原始请求"}
```

`intent` 取值：

- `TEXT`：普通对话、写作、问答，交由现有 DeepSeek 对话能力；
- `IMAGE_GENERATE`：生成图片或编辑图片请求，`prompt` 交给 Qwen-Image；
- `IMAGE_UNDERSTAND`：用户发送图片或明确要求看图，图片与问题交给 Qwen3-VL。

解析失败或未知值一律按 `TEXT` 降级，并记录路由失败日志。

## 调用与回传链路

```text
微信消息
  -> MessageCommand（text + optional image bytes/content type）
  -> DeepSeek IntentRouter
  -> TEXT               -> AiChatService             -> iLink sendText
  -> IMAGE_GENERATE     -> QwenImageClient            -> 下载临时 URL 为字节 -> iLink sendImage
  -> IMAGE_UNDERSTAND   -> QwenVisionClient(Base64)   -> iLink sendText
```

### 生图真实回传不变量

1. Qwen-Image 的 URL 不是用户侧的最终交付物；服务端立即以受控超时下载图片字节。
2. 仅当 iLink 图片上传/发送 API 返回成功，才记录 `IMAGE_REPLIED`。
3. 任何下载、图片格式校验、上传或发送失败均记录 `IMAGE_REPLY_FAILED`，再给用户发送失败说明；不发送 URL 作为替代品。
4. 实施前必须先从当前 `wechat-ilink-sdk` JAR 确认实际图片发送与图片消息字段签名；不得猜测 SDK 方法名。

## 代码设计

- `ai.routing`：`IntentRouter`、`UserIntent`、`DeepSeekIntentRouter`。只做分类，不做图片 API 调用。
- `image.service`：`ImageGenerationService`、`ImageUnderstandingService` 与轻量返回 DTO。
- `image.infrastructure.dashscope`：`DashScopeProperties`、`DashScopeConfig`、`QwenImageClient`、`QwenVisionClient`。负责 HTTP 细节。
- `ilink`：将文本与图片提取改为统一消息命令；回复服务根据意图选择 `sendText` 或图片发送适配器。
- 保留现有异步队列与同用户串行逻辑，模型调用不进入 `pollMessages()` 线程。

## 配置

```properties
dashscope.api-key=${DASHSCOPE_API_KEY:}
dashscope.api-base-url=https://dashscope.aliyuncs.com
dashscope.image-generation-model=qwen-image-2.0
dashscope.vision-model=qwen3-vl-flash
```

真实 Key 只配置在 IDEA Run/Debug Configuration 的 Environment variables 中，或用户本机未纳入版本管理的配置文件中。

## 错误处理与日志

- Key 缺失：快速失败，错误信息只提示变量名。
- 上游超时、限流、内容审核拒绝：映射为可读的用户提示，日志写状态码与请求类型但不写密钥和完整图片 Base64。
- 所有模型调用写入统一事件：`ROUTED`、`IMAGE_GENERATED`、`IMAGE_REPLIED`、`IMAGE_REPLY_FAILED`、`VISION_REPLIED`。

## 验证策略

1. 单元测试：意图 JSON 解析与降级、配置缺失、图片字节校验、生成 URL 解析。
2. 客户端验证：使用测试 Prompt 调用百炼并验证得到有效图片字节。
3. 微信联调：用户发送生图请求后，聊天窗口出现原生图片消息；发送图片加问题后收到文本分析。
4. 失败验收：模拟图片下载/发送失败，验证不发送 URL 且不记录成功。

## 风险与前置条件

- 用户需要在百炼开通模型服务、创建 API Key，并在 IDEA 运行配置中设置 `DASHSCOPE_API_KEY`。
- iLink SDK 的图片收发能力需通过本地已安装 JAR 签名确认；若 SDK 不支持，需要在得到用户确认后评估升级或采用 SDK 官方替代接口。
- 百炼生成 URL 为临时资源，必须在回复任务内立即下载。
