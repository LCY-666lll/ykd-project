修复实时天气上下文误用：提示词要求本轮明确查询当前、实时天气时必须重新调用 WeatherTool；修复识图后图片上下文被清理，允许后续图片编辑；验证：mvn -q -DskipTests compile 通过。

新增定时和周期性任务设计目标：
1. 必须按照现有项目结构接入，优先复用 DeepSeekIntentRouter、AiChatService、现有工具调用链、SQLite/JdbcTemplate、IlinkClientManager 和 iLink 发送逻辑，不引入过重调度框架。
2. 支持一次性定时提醒，例如“下午三点提醒我做某事”。任务必须持久化到 SQLite；项目在触发时间前重启且 iLink 已登录时，到点仍能提醒。
3. 支持周期性任务，例如“每天上午和下午发送最新消息、天气预报”。任务必须持久化；项目关闭期间不执行，若在下一次触发时间前重启，则下一次触发应正常执行。
4. 错过触发时间后重启不补发。一次性任务错过后标记为跳过或完成；周期性任务错过后直接计算下一次未来触发时间。
5. 自动任务执行时必须拥有调用工具资格。执行新闻、天气、时间、翻译、计算等任务时，复用 AiChatService 中的 ChatClient + tools 链路，保证天气和实时新闻等任务会在执行当轮重新调用工具。
6. iLink 主动发送能力已评测：wechat-ilink-sdk 2.3.3 的 ILinkClient 提供 sendText、sendImage、sendFile、sendVoice 等方法；只要扫码建立连接且 client.isLoggedIn() 为 true，即可按保存的 userId 主动发送，不需要等待用户先发消息。
7. 当前代码尚未实现 iLink session-file 的保存和恢复；SDK 支持 resumeContext/exportResumeContext，但项目内未接入。因此第一版调度只保证“项目启动且 iLink 已登录”时发送；是否补齐自动恢复登录作为后续增强单独评估。
8. 取消任务必须通过聊天入口完成。创建任务后微信固定返回取消提示，例如“取消请发送：取消任务 12”；用户后续发送取消语句时，系统识别 TASK_CANCEL 并禁用对应任务。

推荐实现步骤：
1. 新增任务领域模型和持久化表 scheduled_task，记录 id、user_id、task_text、schedule_type、schedule_rule、next_run_at、last_run_at、status、timezone、created_at、updated_at、deleted_at。
2. 新增 ScheduledTaskRepository，风格与 SqliteChatMessageRepository 保持一致，负责创建表、保存任务、查询到期任务、更新 next_run_at、禁用/软删除任务和查询用户任务。
3. UserIntent 增加 TASK_CREATE、TASK_CANCEL、TASK_LIST；DeepSeekIntentRouter 增加本地规则和模型路由提示，识别创建提醒、取消任务、查看任务。
4. 新增任务解析服务，将自然语言解析为保守的结构化规则。第一版优先支持：一次性几点提醒、明天/后天/今天几点、每天一个或多个固定时间点。
5. 新增 ScheduledTaskService，封装创建确认、取消确认、任务列表展示、下一次触发时间计算和错过任务跳过策略。
6. 新增 ScheduledTaskRunner，使用 @Scheduled 固定间隔扫描 SQLite 中 due task；扫描到任务后判断 iLink 是否已登录，未登录不执行且不补发，等待下一轮或下一次未来触发。
7. 新增 AiChatService.executeScheduledTask(userId, taskText)，复用当前工具列表和 ReActTraceAdvisor，自动任务提示词应明确“这是系统按用户设定自动执行的任务，必须根据本轮工具结果回答，不得使用历史旧工具结果代替实时查询”。
8. 自动任务发送复用 iLink 当前发送体系，优先进入现有按 userId 串行的回复队列，避免和用户实时聊天回复交错。
9. 验证目标：mvn -q -DskipTests compile 通过；至少手动验证创建一次性任务、创建每天上午/下午周期任务、取消任务、重启后触发时间前仍发送、错过后不补发。
