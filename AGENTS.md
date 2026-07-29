新增定时提醒功能：用户自然语言创建提醒任务 → SQLite 持久化 → 项目启动恢复 → 到时间通过 iLink 推送。改动清单：
1. ReminderTask.java：修复 corn→cron 拼写，新增 triggerTimeMs 字段支持一次性提醒
2. ReminderTaskRepository.java（新建）：JdbcTemplate 操作 reminder_task 表，@PostConstruct 自动建表
3. ReminderService.java + ReminderServiceImpl.java：接口改为 createReminder(userId,naturalLanguage)/deleteReminder(userId)；实现通过 AI 解析自然语言提取时间与内容，调度后写入 SQLite；@PostConstruct 从 SQLite 恢复任务并重新调度；到时间调用 IlinkMessageReplyService.reply() 推送
4. DeepSeekIntentRouter.java：新增 CREATE_TASK/DELETE_TASK 的本地规则匹配 + 模型路由提示词
5. IlinkReplyProcessor.java：注入 ReminderService，新增 CREATE_TASK/DELETE_TASK 意图分发
6. IlinkMessageReplyServiceImpl.java：注入 IlinkClientManager，实现 reply(userId,content) 通过 ILinkClient.sendText() 主动推送
未改动文件：DynamicSchedulerTool、SchedulerConfig、AiChatServiceImpl、pom.xml、application.properties 均保留原样；
验证：mvn -q -DskipTests compile 通过。

修复定时提醒循环依赖：ReminderServiceImpl 移除 IlinkMessageReplyService 依赖，改为直接注入 IlinkClientManager.sendText() 发送提醒消息；
断链：ReminderServiceImpl → IlinkClientManager（单向），不再形成 Impl → ReplyService → ReplyProcessor → ReminderService 循环；
验证：mvn -q -DskipTests compile 通过。

修复提醒时间解析错误：AI 不再直接生成绝对时间戳 triggerTimeMs，改为语义解析 → Java 计算绝对时间。
改动清单：
1. PARSE_PROMPT 重构：AI 输出 delaySeconds（相对时长秒数）或 targetTime（HH:mm 钟点时间）替代原来的 triggerTimeMs 绝对时间戳；提示词中严禁 AI 输出 triggerTimeMs 字段
2. ReminderServiceImpl 新增 computeTriggerTimeMs()：根据 AI 返回的 delaySeconds（now + delaySeconds*1000）或 targetTime（结合当前日期推算，若已过期则自动推到次日）计算绝对时间戳
3. createReminder() 增加时间校验：调度前检查 triggerTimeMs > System.currentTimeMillis()，否则拒绝创建并返回错误提示
4. ReminderTask DTO 不变，triggerTimeMs 字段保留用于持久化和调度，仅改变其计算来源
未改动文件：RaminderTask.java、ReminderTaskRepository.java、DynamicSchedulerTool.java、ReminderService.java 均保留原样；
验证：mvn -q -DskipTests compile 通过。

修复提醒推送 contextToken 缺失问题：全链路持久化 contextToken，避免 SDK 内部缓存失效导致推送失败。
改动清单：
1. ReminderTask.java：新增 contextToken 字段 + getter/setter
2. ReminderTaskRepository.java：建表新增 context_token TEXT 列；INSERT/SELECT_ALL/SELECT_BY_USER 全部包含 contextToken 读写
3. ReminderService.java：createReminder() 签名增加 contextToken 参数
4. ReminderServiceImpl.java：createReminder() 保存 contextToken 到 ReminderTask；executeReminder() 日志记录 contextToken 状态
5. IlinkMessagePollingService.java：handleMessage() 中通过 WeixinMessage.getContext_token() 提取 contextToken，传递到 submit()/submitVoice()
6. IlinkMessageReplyService.java + IlinkMessageReplyServiceImpl.java：submit()/submitVoice() 增加 contextToken 参数，透传到 process()
7. IlinkReplyProcessor.java：process() 增加 contextToken 参数，CREATE_TASK 时传递到 reminderService.createReminder()
说明：当前 SDK sendText(userId,text) 不支持直接传入 contextToken，contextToken 已全链路持久化，服务重启后恢复任务时 contextToken 仍可用；后续 SDK 升级后可直接替换 sendText 调用；
验证：mvn -q -DskipTests compile 通过。

完善提醒推送 contextToken 发送链路 + 定时任务支持 AI Agent / Tool Calling：
改动清单：
1. IlinkClientManager.java：新增 sendText(userId, text, contextToken) 重载方法，封装带 contextToken 的主动推送逻辑；
   contextToken 当前用于日志追踪，若 SDK 后续升级支持 sendText(userId, text, contextToken) 可直接解注释替换
2. ReminderServiceImpl.java 重大升级：
   a. 注入 WeatherTool、BaiduSearchTool、TimeTool、MathCalculatorTool、TranslationTool 五个工具
   b. 新增 agentChatClient（无 MemoryAdvisor 的独立 ChatClient 实例，避免提醒执行污染对话历史）
   c. 新增 AGENT_PROMPT 系统提示词：告知模型这是定时提醒触发，查询类内容必须调用工具，简单提醒类直接生成文本
   d. executeReminder() 全量重写：
      - 步骤1：调用 agentChatClient.prompt().system(AGENT_PROMPT).user(content).tools(...).call() 由 AI 判断是否调用工具
      - 步骤2：使用 clientManager.sendText(userId, answer, contextToken) 发送 AI 生成结果，传递持久化 contextToken
      - 步骤3（降级）：AI 调用异常时 fallback 到直接发送原始提醒内容
      - 步骤4：一次性任务触发后自动清理（cron 任务保留）
      - 对比旧流程（scheduler 触发 → client.sendText(固定文本)），新流程（scheduler 触发 → ChatClient + Tool Calling → AI 生成回复 → sendText with contextToken）
3. 调用链完整流程：
   用户发送消息
     ↓
   IlinkMessagePollingService.handleMessage() 提取 contextToken
     ↓
   IlinkMessageReplyServiceImpl.reply() 串行处理
     ↓
   IlinkReplyProcessor.process() 意图路由 → CREATE_TASK
     ↓
   ReminderService.createReminder(userId, contextToken, userText)
     ↓
   AI 解析自然语言 → 计算触发时间 → ReminderTask 保存到 SQLite（含 contextToken）
     ↓
   DynamicSchedulerTool 调度（一次性 scheduleAt / cron scheduleCron）
     ↓
   时间到达 → Scheduler 线程触发
     ↓
   ReminderServiceImpl.executeReminder(ReminderTask)
     ↓
   ChatClient.prompt().tools(...).call() → AI Agent 决策是否调用工具
     ↓
   IlinkClientManager.sendText(userId, answer, contextToken)
     ↓
   iLink 发送到微信
4. 设计决策：
   - 不注入 IlinkMessageReplyService（避免 ReminderService → ReplyService → ReplyProcessor → ReminderService 循环依赖）
   - agentChatClient 使用 ChatClient.Builder.build() 创建无 MemoryAdvisor 的独立实例（提醒执行是独立任务，不应参考对话历史）
   - 工具注册与 AiChatServiceImpl 使用相同的 WeatherTool/TimeTool/BaiduSearchTool/MathCalculatorTool/TranslationTool 实例
   - contextToken 不视为登录 token，仅用于恢复 iLink 会话上下文
未改动文件：ReminderTask.java、ReminderTaskRepository.java、ReminderService.java、DeepSeekIntentRouter.java、IlinkReplyProcessor.java、IlinkMessagePollingService.java、IlinkMessageReplyService.java、IlinkMessageReplyServiceImpl.java、DynamicSchedulerTool.java、SchedulerConfig.java、AiChatServiceImpl.java 均保留原样；
验证：mvn -q -DskipTests compile 通过。

修复重新登录后提醒推送失败 + 推送失败时任务被误删的问题：
问题根因：
1. executeReminder() 中一次性任务推送失败后仍执行清理（activeTasks.remove + repository.deleteById），导致任务从 DB 消失
2. 重新扫码登录后 ILinkClientManager.createNewClient() 创建新的 ILinkClient，SDK 内部 context（userId -> contextToken）为空，sendText 调用 requireContext() 抛出 "missing latest context token"，持久化的 contextToken 属于旧 session 已被 SDK 忽略

改动清单：
1. ReminderServiceImpl.java 核心改动：
   a. 新增 retryCountMap（taskId -> 重试次数）+ MAX_RETRIES=10、BASE_RETRY_DELAY_MS=15s、MAX_RETRY_DELAY_MS=60s 重试参数
   b. executeReminder() 重写：引入 pushed 标记，只在推送成功后调用 cleanupOneShot；推送失败交给 handlePushFailure 统一处理
   c. 新增 handlePushFailure()：判断是否 contextToken 过期错误，是则递进延迟重试（15s/30s/45s/60s...），最多 10 次；非 context 错误直接清理
   d. 新增 cleanupOneShot()：只清理一次性任务（cron 任务保留），同时清理 activeTasks、repository、retryCountMap
   e. 新增 isContextTokenError()：判断是否为 context token 过期场景（首次重试或未超过上限）
   f. 新增 onUserMessage(userId, contextToken)：实现 ReminderService 接口；用户发送消息时更新 DB 中的 contextToken 并重置重试计数器，使等待中的重试任务能在下次触发时成功推送
   g. 导入 ILinkException
2. ReminderService.java：接口新增 onUserMessage(String userId, String contextToken) 方法
3. ReminderTaskRepository.java：新增 updateContextToken(userId, contextToken) 方法，批量更新用户所有任务的 context_token
4. IlinkMessagePollingService.java：注入 ReminderService，在 handleMessage() 中每次收到用户消息时调用 reminderService.onUserMessage(fromUserId, contextToken)

重试机制工作流程：
  提醒到期 → executeReminder
    ↓ AI Agent 生成回复
    ↓ sendText（SDK 内部 requireContext）
    ├─ 成功 → cleanupOneShot（删除任务）
    └─ 失败（missing latest context token）
        → handlePushFailure → 递进延迟重试（15s/30s/45s/…/60s）
        → 用户重新扫码登录后发消息
        → PollingService 调用 onUserMessage → 更新 contextToken + 重置重试计数
        → 下次重试到达 → SDK 已收到用户消息恢复上下文 → sendText 成功 → cleanupOneShot

未改动文件：ReminderTask.java、DeepSeekIntentRouter.java、IlinkReplyProcessor.java、IlinkMessageReplyService.java、IlinkMessageReplyServiceImpl.java、IlinkReplySender.java、IlinkClientManager.java、IlinkLoginService.java、IlinkLoginServiceImpl.java、DynamicSchedulerTool.java、SchedulerConfig.java、AiChatServiceImpl.java 均保留原样；
验证：mvn -q -DskipTests compile 通过。

修复三个提醒功能 Bug：
Bug#1（P0）：createReminder() 增加 UNKNOWN 类型短路判断。AI 返回 type=UNKNOWN 时（如用户追问/抱怨），
不再进入 computeTriggerTimeMs() 避免抛 RuntimeException，而是直接返回 AI 解析的 content 作为对话文本。
Bug#2（P0）：computeTriggerTimeMs() 增加容差窗口。用户在同一分钟内发消息设置提醒（如 11:28 发 "11.28提醒我喝水"），
目标时间已过但差距在 2 分钟内时立即触发（now+1s），而非推到明天。已过 2 分钟以上才推到次日。
Bug#3（P1）：DeepSeekIntentRouter 新增 TASK_NEGATIVE_PATTERN 排除规则。疑问句（为什么/怎么/为啥开头）、
抱怨句（没提醒我/提醒呢/怎么没/为什么没）、查询句（有什么提醒/提醒列表/查看提醒）不再被 LOCAL_RULE 误判为
CREATE_TASK 或 DELETE_TASK，交由模型路由走 TEXT 对话回复。
改动清单：
1. ReminderServiceImpl.java：
   a. createReminder()：content 校验后增加 type==UNKNOWN 短路，返回 content 作为对话文本
   b. computeTriggerTimeMs()：targetTime 计算改用 diffMs 判定，过去 2 分钟内立即触发，未来正常调度
2. DeepSeekIntentRouter.java：
   a. 新增 TASK_NEGATIVE_PATTERN 正则：匹配疑问/抱怨/查询模式
   b. matchExplicitIntent()：CREATE_TASK/DELETE_TASK 检查前先判定 TASK_NEGATIVE_PATTERN，命中则返回 Optional.empty()
未改动文件：ReminderTask.java、ReminderTaskRepository.java、ReminderService.java、DynamicSchedulerTool.java 等均保留原样；
验证：mvn -q -DskipTests compile 通过。
