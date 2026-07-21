# 项目协作台账

> 本文件是本项目的持续状态源。每完成一个阶段，必须同步更新：业务要求、代码结构、配置、接口/数据读写、验证结果与遗留风险。

## 当前目标（2026-07-18）

在现有微信 iLink Bot 上完成图片能力闭环：

1. 使用本地环境变量配置国内图片模型；
2. 使用 DeepSeek 根据用户文本或图片识别意图并路由；
3. 支持文本回复、文生图和看图；
4. 文生图成功后，Bot 必须向用户发送真实微信图片，而不是仅发送 URL。

## 已确认的业务决策

| 决策项 | 结论 | 原因 |
| --- | --- | --- |
| 文本模型与路由模型 | 现有 DeepSeek `deepseek-chat` | 复用已接通能力，使用结构化 JSON 意图路由。 |
| 图片平台 | 阿里云百炼 | 国内平台，一套 API Key 覆盖生图和看图。 |
| 文生图模型 | `qwen-image-2.0` | 同步调用，支持文生图及图片编辑，适合首版 Bot。 |
| 看图模型 | `qwen3-vl-flash` | 低延迟视觉理解及 OCR。 |
| 生图回传规则 | 必须发送图片二进制 | URL 仅用于服务端下载；微信发送图片失败必须显式报错，不能伪报成功。 |

## 当前架构（勘察结果）

```text
WeChat iLink
  -> IlinkMessagePollingService
  -> IlinkMessageReplyServiceImpl（异步、同用户串行）
  -> AiChatService
  -> DeepSeekClient
  -> iLink sendText
```

现状：仅处理文本；图片消息被忽略；仅有 `sendText` 回传。

## 目标结构（待实现）

```text
ilink
  message: 入站微信文本/图片标准化与回传
ai
  routing: DeepSeek JSON 意图识别
  chat: 普通文本对话
image
  service: ImageGenerationService / ImageUnderstandingService
  infrastructure.dashscope: 百炼 API 客户端与配置
```

## 环境变量与配置

| 名称 | 用途 | 是否允许提交 Git |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 现有文本对话与意图路由 | 否 |
| `DASHSCOPE_API_KEY` | 百炼生图、看图 | 否 |

`application.properties` 仅保留 `${DASHSCOPE_API_KEY:}` 形式的引用，不写入真实密钥。

## 分阶段任务与成果

| 阶段 | 状态 | 内容 | 可验证成果 |
| --- | --- | --- | --- |
| 0. 勘察与模型选择 | 完成 | 现状勘察；选择百炼双模型方案 | 本台账与设计文档。 |
| 1. 本地配置 | 进行中 | 在 IDEA Run Configuration 配置百炼 Key；新增不含密钥的配置项 | 应用可读取 Key，缺失时给出明确错误。 |
| 2. 图片基础设施 | 未开始 | 百炼生图、看图客户端；DTO；超时与异常映射 | 可用测试/接口独立验证模型请求。 |
| 3. 意图路由 | 未开始 | DeepSeek 输出严格 JSON 并分发三种意图 | 文本、生图、看图各有稳定路由结果。 |
| 4. 微信图片闭环 | 未开始 | 接收图片、下载/编码、调用视觉模型、上传并发送生成图片 | 用户收到真实图片消息。 |
| 5. 验证与文档收尾 | 未开始 | 单测、人工联调、更新本文件 | 构建通过，验收记录完整。 |

## 数据读写边界（首版）

- 不新增数据库、表或持久化 CRUD。
- 读取：微信消息、环境变量、百炼 API 响应。
- 写入：仅应用日志与 iLink 图片发送请求。
- 生成图临时字节仅在内存处理；不落盘，不长期保存用户图片。

## 不变量与失败策略

1. 任何密钥不得写入源码、`application.properties`、日志或 Git。
2. DeepSeek 路由 JSON 无法解析时，降级为普通文本对话。
3. 生图 API 成功后必须获取可读取的图片字节并调用 iLink 的图片发送方法；二者都成功才记录 `IMAGE_REPLIED`。
4. 图片下载、上传或发送失败时，记录失败原因并向用户发送明确的失败提示；不得把 URL 当作图片结果，也不得记录成功。
5. 不阻塞微信轮询线程；所有模型调用仍走现有后台回复线程池。

## 最近更新

- 2026-07-18：建立台账；确认百炼 `qwen-image-2.0` + `qwen3-vl-flash`；确认真实图片回传为强制验收条件。

## 2026-07-18 实施更新

- 本地永久环境变量 `DASHSCOPE_API_KEY` 已由用户配置；配置文件仅引用该变量。
- 百炼配置层已迁移并落在 `ai.infrastructure.dashscope`：`DashScopeProperties`、`DashScopeConfig`。
- 已确认官方 iLink SDK 可直接使用 `downloadImageFromMessageItem(...)` 接收图片及 `sendImage(userId, bytes, fileName, mimeType)` 发送真实图片。
- 当前进行中：`QwenImageClient` 调用 `qwen-image-2.0` 同步接口并取得临时图片 URL；下一步必须立即下载图片字节后再调用 `sendImage`，不允许把 URL 回复给用户。
- 编译验证：`mvn -q -DskipTests compile` 通过（配置层完成时）。
- 2026-07-18：新增 `QwenImageClient`、`ImageGenerationService` 与 `GeneratedImage`；生图 URL 在内存中立即下载为字节。下载客户端不携带 Authorization，避免百炼 API Key 泄露到 OSS。`mvn -q -DskipTests compile` 通过。
## 2026-07-18 图片上下文与识图实施更新

- 新增按微信用户隔离的内存图片上下文：收到单独图片后通过 iLink SDK 的 downloadImageFromMessageItem 方法下载字节并暂存；不向用户自动发送占位回复。
- 图片上下文只保留最近一张图片，存活 10 分钟；识图文本发送成功后清理，调用失败时保留以便用户再次追问；定时任务每分钟清理过期内容。
- 用户后续发送任意文本时，回复线程优先读取该用户的待分析图片，调用 qwen-vl-plus 视觉模型；没有待分析图片才继续原有 DeepSeek 意图路由与文生图链路。
- 新增 QwenVisionClient、ImageUnderstandingService、ImageContextService 与对应 DTO；视觉请求使用百炼 OpenAI 兼容接口 /compatible-mode/v1/chat/completions，图片使用 Base64 Data URL，图片字节不落盘、不入库。
- 日志新增：[IMAGE_CONTEXT_SAVED]、[IMAGE_UNDERSTANDING]、[IMAGE_UNDERSTOOD]、[IMAGE_CONTEXT_SAVE_FAILED]。
- 验证：mvn -q test 通过；新增内存图片上下文保存、读取、清除单测。待人工联调：微信先单独发图，再发“这张图怎么样”。
- 交互调整：图片暂存成功后，固定提示“已经看到您的图片啦，您想了解什么呢？”。该提示也进入同一用户顺序回复队列，保证其一定先于用户后续追问的识图结果发送。
- 日志补充：[IMAGE_CONTEXT_QUEUED]、[IMAGE_CONTEXT_REPLIED]、[IMAGE_CONTEXT_REPLY_FAILED]；验证：mvn -q test 再次通过。
## 2026-07-18 图片上下文统一路由更新

- 修正图片上下文的分发逻辑：有待分析图片时不再直接识图，所有后续文字均先交给 DeepSeek 选择 TEXT、IMAGE_UNDERSTAND 或 IMAGE_EDIT。
- IMAGE_UNDERSTAND：调用 qwen-vl-plus 并在成功发送识图文字后清理图片。
- IMAGE_EDIT：将暂存原图按 Base64 Data URL 传入 qwen-image-2.0，同步生成或编辑后通过 iLink sendImage 返回真实图片；成功后清理图片。
- TEXT：保持现有 DeepSeek 文本回复，图片仍暂存至过期，便于用户随后继续请求识图或编辑。
- 无待分析图片时保持既有 TEXT 与 IMAGE_GENERATE 路由和文生图实现不变。
- 日志补充：[IMAGE_EDITING]、[IMAGE_EDITED]；验证：mvn -q test 通过。待人工联调：图片后分别发送“这张图怎么样”“参考这个图生成水彩风图片”“今天天气如何”。
- 修正图片上下文误引用：有图片时的 DeepSeek 枚举新增 IMAGE_GENERATE；用户未明确说“参考/基于/修改这张图”而只说生成图片时，走原有纯文生图，不传入原图。
- 用户发出与图片无关的 TEXT 后，文本回复成功即清理该用户图片上下文，避免后续请求误参考旧图。
- 收图确认提示改用 iLink SDK 的 sendTextWithTyping，持续 800 毫秒；模型回复链路保留 startTyping 与 stopTyping，并记录对应日志。
- 验证：mvn -q test 通过。
- DeepSeek 路由提示词改为语义边界说明：不再列举触发关键词；由模型判断“理解当前图片”“把当前图片作为输入”“独立新图”或“普通文字任务”。
- 验证：mvn -q test 通过。建议按分支测试清单进行人工联调，并以 ROUTED、IMAGE_CONTEXT_CLEARED、IMAGE_UNDERSTOOD、IMAGE_EDITED 日志核对真实路由。
## 2026-07-18 Windows 控制台乱码修复

- 本地 IDEA 控制台按 Windows GBK 解码，而应用此前以 UTF-8 输出，导致日志中的中文消息显示为乱码。
- 控制台日志编码调整为 GBK；文件日志继续使用 UTF-8。
- IDEA 项目编码明确覆盖 main Java、resources、test Java 为 UTF-8；Maven 增加 project.build.sourceEncoding=UTF-8，避免源码与资源在编译阶段发生编码歧义。
- 验证：mvn -q test 通过。重启应用后验证 IDEA 控制台中文日志。
- 更正控制台编码判断：GBK 输出在当前 IDEA 控制台显示为替换字符，已恢复 logging.charset.console=UTF-8；文件日志保持 UTF-8。重启后以 UTF-8 验证中文日志。
## 2026-07-18 回复内容日志恢复

- REPLIED 日志恢复输出 Bot 普通文字 answer；IMAGE_UNDERSTOOD 日志恢复输出识图 answer；IMAGE_CONTEXT_REPLIED 输出固定收图确认文本。
- 长回复统一压缩为单行，并限制前 1000 字符，避免控制台被大段内容淹没。
- 验证：mvn -q test 通过。
## 2026-07-20 协作原则与乱码核查

- 后续所有功能接入坚持小步改动：只改当前需求相关代码，不做大范围重构，不破坏已跑通的文字、图片、语音链路。
- 每次代码变更后必须同步更新本文件，记录改动内容、验证结果与遗留风险。
- 已核查 `src/main/java` 与 `src/main/resources` 下的中文提示、注释和配置说明，未发现残留中文乱码；仅清理本文件历史记录中的乱码示例表达。
- 已查阅 Spring AI 2.0.0 官方 ChatMemory/ChatClient 文档、Spring AI Alibaba chat-memory 示例与 Spring AI 默认 conversationId 安全公告；后续记忆实现必须用微信 userId 作为显式 conversationId，避免多用户上下文串线。
## 2026-07-20 Spring AI 记忆第一步接入

- 已按官方当前文档选择 Spring AI 2.0.0；该版本已发布 GA，支持 Spring Boot 4.0.x/4.1.x，本项目当前 Spring Boot 4.1.0 可直接接入。
- `pom.xml` 新增 `spring-ai-bom` 2.0.0 与 `spring-ai-starter-model-deepseek`，不替换现有自写 DeepSeek 路由和图片/语音模型客户端。
- `application.properties` 新增 `spring.ai.deepseek.*` 与 `spring.ai.model.chat` 配置，继续复用 `DEEPSEEK_API_KEY`，不写入任何真实密钥。
- 新增 `SpringAiChatConfig`，在 Spring AI `ChatModel` 存在时创建带 `MessageChatMemoryAdvisor` 的 `ChatClient`，由框架默认 `MessageWindowChatMemory` 提供短期上下文。
- `AiChatService` 新增 `chat(conversationId, message)`，旧 `chat(message)` 保留给控制器兼容；实现层优先使用 Spring AI ChatClient 并显式传入 `ChatMemory.CONVERSATION_ID`，不可用时回退原 `DeepSeekClient`。
- iLink 普通文本/语音文本回复链路调用 `aiChatService.chat(userId, userText)`，以微信用户 id 作为 conversationId，避免多用户记忆串线。
- 本阶段只让普通聊天回复具备 Spring AI 记忆基础；图片、识图、生图、语音文件回复、TTS、iLink SDK 语音识别链路未做行为改造。
- 验证：`mvn -q -DskipTests compile` 通过；`mvn -q test` 通过。
- 下一步：补充统一多模态事件写入服务，把图片接收、识图结果、生成图结果、语音识别文本与语音回复文本写入同一个 Spring AI conversationId。
