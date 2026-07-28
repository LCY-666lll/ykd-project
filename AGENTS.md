修复实时天气上下文误用：提示词要求本轮明确查询当前、实时天气时必须重新调用 WeatherTool；修复识图后图片上下文被清理，允许后续图片编辑；验证：mvn -q -DskipTests compile 通过。

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



