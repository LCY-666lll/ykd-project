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

微信机器人接入 MCP 浏览器操作能力设计目标：
1. 最终形态必须服务当前项目业务：用户在微信中发起自然语言浏览器任务，机器人通过真实浏览器访问公开网页、点击、填写、筛选、读取页面结果，并通过现有 iLink 文本回复链路返回操作结果。
2. 第一版必须最大复用现有链路，不做突兀架构：优先复用 DeepSeekIntentRouter、UserIntent、AiChatServiceImpl、Spring AI ChatClient tools 调用方式、ReActTraceAdvisor、IlinkReplyProcessor 和现有按 userId 串行回复逻辑。
3. MCP 选型采用 Spring AI MCP Client + Microsoft Playwright MCP。项目当前 Java 21、Spring Boot 3.5.15、Spring AI 1.1.2 与 spring-ai-starter-mcp-client:1.1.2 兼容；本机已验证 @playwright/mcp@0.0.78 可启动、完成 MCP STDIO 握手、返回 tools/list，并成功访问 https://example.com 获取页面快照。
4. 第一版传输优先采用本地 STDIO MCP，不优先采用 HTTP/SSE。原因是 STDIO 依赖更少、部署更贴近单体 Spring Boot 项目，并规避 HTTP session 维护带来的额外不稳定因素。
5. MVP 只支持公开网页的低风险操作：navigate、snapshot、find、click、type/fill、select、wait、tabs、close、必要时截图。不支持登录、验证码、短信/扫码、支付、购买、删除、发布、上传文件、任意 JavaScript 执行、读取本地文件或绕过网站安全策略。
6. 业务场景优先落在“有真实微信助手价值且两到三天可实现”的任务：查询官网通知/公告、学校或公司页面信息、招聘/招标/活动列表、公开商品或库存页面、需要点击筛选或翻页才能看到的信息。普通搜索能直接回答的问题仍优先走现有 BaiduSearchTool，不强行走浏览器。
7. 安全边界必须清晰：浏览器任务必须有站点白名单或业务域名限制；危险动词需要拒绝或降级为只读建议；页面快照和操作轨迹不得完整写入长期记忆，避免把网页大量内容污染用户记忆。
8. 稳定性目标：第一版限制单用户短任务、有限步骤、有限超时；浏览器任务失败时返回明确原因，例如页面不可访问、元素找不到、疑似验证码、超时或超出安全范围。
9. 推荐最小实现方向：UserIntent 增加 BROWSER_TASK；DeepSeekIntentRouter 增加本地规则和模型提示；新增 BrowserTaskService 封装浏览器任务提示词、安全边界和工具调用；AiChatServiceImpl 对 BROWSER_TASK 使用带 MCP toolCallbacks 的 ChatClient；回复继续复用 AiChatResponse 文本和 IlinkReplyProcessor。
10. 第一阶段验收标准：只完成设计确认和文档记录，不修改 Java 业务代码。后续每个阶段必须先说明阶段目的、方案、验收成果、将要改的代码和操作，经用户同意后再执行。
MCP 浏览器操作能力第二阶段最小落地技术方案：
1. 第二阶段结论：采用 Spring AI MCP Client 1.1.2、Microsoft Playwright MCP 0.0.78、STDIO 传输和独立 BROWSER_TASK 业务分支。该方案已经通过用户审批，本阶段只记录设计，不修改 Java 业务代码。
2. 最终业务形态：用户在微信中发送包含明确公开网址的自然语言任务，例如“打开某学校官网，进入通知公告，筛选 2026 年招聘信息，查看前三条并告诉我截止时间”；系统实际控制 Edge 或 Chrome 完成导航、查找、点击、填写、筛选、等待和读取，并通过现有 iLink 文字回复返回最终结果。
3. 调用链固定为：微信消息 → DeepSeekIntentRouter → BROWSER_TASK → IlinkReplyProcessor → BrowserTaskService → 现有 ChatClient 和 ReActTraceAdvisor → Spring AI MCP Client → Playwright MCP → 真实浏览器 → 最终摘要 → 现有 iLink 文字回复。
4. 不把 MCP 浏览器工具注册到所有普通聊天。只有 BROWSER_TASK 执行时才向 ChatClient 提供经过筛选的 MCP ToolCallback，避免普通聊天误调用浏览器，并避免扩大天气、搜索、图片、文件和记忆链路的影响范围。
5. BrowserTaskService 使用每次任务独立的临时 conversationId，任务完成或失败后清理临时 ChatMemory。用户原始请求和最终文字摘要可以沿用现有聊天记录逻辑，完整页面快照、工具响应和浏览器操作轨迹不得进入用户长期记忆。
6. 第一版允许的 MCP 工具限定为公开网页只读或低风险操作，包括 browser_navigate、browser_snapshot、browser_find、browser_click、browser_type、browser_fill_form、browser_select_option、browser_wait_for、browser_tabs、browser_navigate_back、browser_take_screenshot 和 browser_close；实际工具名以固定版本 tools/list 为准。
7. 第一版明确排除 browser_evaluate、browser_run_code_unsafe、browser_file_upload 以及登录、验证码、短信或扫码验证、支付、购买、删除、发布、上传文件、读取本地文件、绕过反爬或网站安全机制等高风险能力。
8. 第一版要求用户提供明确公开网址，并通过配置维护允许访问的业务域名。普通搜索能够直接回答时继续使用现有 BaiduSearchTool；只有需要真实网页点击、填写、筛选、分页或动态读取时才进入 BROWSER_TASK。
9. 稳定性约束：Playwright MCP 版本固定为 0.0.78；Node.js 要求不低于 18；Windows STDIO 使用 cmd.exe /c 调用 npx；MCP 功能必须有独立启用开关；导航和单次操作设置有限超时；继续复用 ReActTraceAdvisor 的最多 8 轮工具调用限制；失败时区分未启用、MCP 未启动、网址不允许、页面不可访问、元素找不到、疑似验证码、工具超时和超出安全范围。
10. 推荐配置默认为关闭 MCP 浏览器功能，确保 Node、npx 或浏览器环境缺失时不影响微信机器人原有能力；部署或演示环境完成依赖检查后再通过环境变量启用。BrowserTaskService 使用可选 MCP Provider，在功能关闭时返回明确提示，不让整个 Spring Boot 应用启动失败。
11. 预计生产代码改动范围：pom.xml 增加 spring-ai-starter-mcp-client；application.properties 增加启用开关、SYNC/STDIO、请求超时、Playwright 参数和允许域名；UserIntent 增加 BROWSER_TASK；DeepSeekIntentRouter 增加路由规则；新增职责单一的 BrowserTaskService；IlinkReplyProcessor 增加一个文字结果分支。现有微信发送协议、回复队列、数据库表和长期记忆结构不修改。
12. 预计测试改动范围：DeepSeekIntentRouterTest 增加明确浏览器操作与普通搜索的区分测试；IlinkReplyProcessorTest 增加 BROWSER_TASK 分流测试；BrowserTaskService 测试覆盖功能关闭、域名拒绝、允许网址、工具筛选、任务结果和异常降级。
13. 实现阶段验收用例至少包括：打开允许域名的公开页面并返回标题；点击链接后返回目标内容；完成一次筛选或表单填写并提取结果；普通“搜索某信息”仍走 TEXT；禁止域名被拒绝；危险操作被拒绝；MCP 关闭或启动失败不影响普通聊天；mvn -q -DskipTests compile 和相关单元测试通过。
14. 预计实现周期为两天：第一天完成依赖、配置、意图分流、BrowserTaskService 和单元测试；第二天完成真实网页联调、失败提示、记忆隔离检查和微信端到端验收。目标网站出现验证码、强反爬或页面结构变化属于外部风险，第一版如实返回原因，不尝试绕过。
15. 下一阶段仍需单独审批：先完成“MCP 基础接入和启动验证”，只修改依赖、配置和最小 MCP 可用性测试；该阶段通过后再接入微信 BROWSER_TASK 业务链路。
MCP 浏览器操作能力第三阶段依赖接入记录：
1. 已确认 pom.xml 的 dependencies 中存在 spring-ai-starter-mcp-client，不单独声明版本，由项目现有 spring-ai-bom 和 spring-ai.version=1.1.2 统一管理，确保与现有 Spring AI 组件保持版本兼容。
2. 已确认 spring-ai-starter-model-deepseek 只保留项目原有的一份声明，spring-ai-starter-mcp-client 只存在一份声明，不删除或改变任何原有模型能力。
3. 本次依赖接入没有修改 Java、application.properties、微信回复链、数据库、工具调用链或长期记忆逻辑。
4. 已执行 mvn -q -DskipTests compile 并通过；后续配置和业务接入仍需逐项展示并经用户批准后执行。
MCP 浏览器操作能力第三阶段配置整理记录：
1. 已整理 application.properties 的 MCP 浏览器配置段，所有新增说明使用中文；只修改该配置段，不覆盖 DeepSeek、DashScope、iLink、数据库、现有工具或记忆配置。
2. spring.ai.mcp.client.enabled 默认读取 BROWSER_MCP_ENABLED，缺省值为 false；默认不创建 MCP 客户端、不启动 Playwright 和浏览器，确保新增依赖与配置不影响原有业务启动和调用链。
3. MCP 客户端使用 SYNC 类型、启动期初始化、30 秒协议请求超时和 ToolCallback 转换，与当前 Spring MVC、同步 ChatClient 和微信回复链保持一致。
4. Windows STDIO 使用 cmd.exe /c 和拆分后的参数启动 npx；Playwright MCP 固定为 0.0.78，使用 headless、isolated、msedge、5 秒操作超时、30 秒导航超时和 full 页面快照。
5. 本阶段不启用 MCP、不启动浏览器、不添加 BROWSER_TASK、不修改任何 Java 或测试代码；只执行默认关闭状态下的配置核对和 mvn -q -DskipTests compile。