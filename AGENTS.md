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
10. 当前项目配置默认开启 MCP 浏览器功能；部署或演示环境必须先完成 Node、npx 和浏览器依赖检查。BrowserTaskService 仍使用可选 MCP Provider，若通过环境变量关闭功能则返回明确提示，不让整个 Spring Boot 应用启动失败。
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
2. spring.ai.mcp.client.enabled 默认读取 BROWSER_MCP_ENABLED，缺省值为 true；默认创建 MCP 客户端并启动 Playwright 浏览器，部署环境必须具备对应依赖。仍可通过环境变量显式关闭以停用该能力。
3. MCP 客户端使用 SYNC 类型、启动期初始化、30 秒协议请求超时和 ToolCallback 转换，与当前 Spring MVC、同步 ChatClient 和微信回复链保持一致。
4. Windows STDIO 使用 cmd.exe /c 和拆分后的参数启动 npx；Playwright MCP 固定为 0.0.78，使用 headless、isolated、msedge、5 秒操作超时、30 秒导航超时和 full 页面快照。
5. 本阶段不启用 MCP、不启动浏览器、不添加 BROWSER_TASK、不修改任何 Java 或测试代码；只执行默认关闭状态下的配置核对和 mvn -q -DskipTests compile。

MCP 浏览器操作能力第四阶段启动与工具发现验证记录：
1. 新增 PlaywrightMcpStartupIntegrationTest，使用 ApplicationContextRunner 仅加载 Spring AI MCP 的 STDIO、客户端和 ToolCallback 自动配置，不启动完整 Spring Boot，不扫描项目业务包。
2. 测试通过 ConfigDataApplicationContextInitializer 读取现有 application.properties，仅在测试上下文覆盖 spring.ai.mcp.client.enabled=true。
3. 已验证 playwright 连接成功绑定 cmd.exe、npx、@playwright/mcp@0.0.78、msedge、超时和快照参数。
4. 已验证 Spring AI 通过 STDIO 完成 MCP 初始化和工具发现，并生成 SyncMcpToolCallbackProvider；工具列表至少包含 browser_navigate、browser_snapshot、browser_click 和 browser_close。
5. 测试由 RUN_BROWSER_MCP_INTEGRATION_TEST=true 显式启用，普通测试默认不会启动 Playwright MCP。
6. 测试上下文未加载 iLink、SQLite、DeepSeek、DashScope、长期记忆和微信消息轮询组件。
7. 已执行 mvn -q -DskipTests compile 并通过；首次 MCP 测试因受限环境禁止访问 npm 缓存外资源而失败，未产生残留进程；随后在允许访问 npm 的环境中执行指定测试并通过。
8. 成功验证时 Playwright MCP 返回协议版本 2024-11-05 和 tools 能力；测试退出码为 0，测试前后相关进程数量均为 17，没有本阶段新增的 Playwright MCP、Node、Edge 或 Java 残留进程。
9. 本阶段只验证 MCP 启动、配置绑定和工具发现，尚未增加 BROWSER_TASK、工具安全过滤、域名限制或微信业务接入。

MCP 浏览器操作能力第五阶段安全工具过滤记录：
1. 新增 BrowserMcpToolProviderTest，覆盖 MCP Provider 缺失时返回空工具数组、白名单工具保留、危险及未知工具过滤、BrowserMcpToolProvider 不注册为全局 ToolCallbackProvider。
2. BrowserMcpToolProvider 继续使用 Optional<SyncMcpToolCallbackProvider>；当前 MCP 默认开启，但通过环境变量显式关闭时仍不会因为缺少 Provider 影响应用启动。
3. 白名单仅允许 browser_navigate、browser_snapshot、browser_find、browser_click、browser_type、browser_fill_form、browser_select_option、browser_wait_for、browser_tabs、browser_navigate_back、browser_take_screenshot 和 browser_close；browser_evaluate、browser_run_code_unsafe、browser_file_upload 及未知工具默认不放行。
4. 本阶段没有修改 BrowserMcpToolProvider、BROWSER_TASK、DeepSeekIntentRouter、BrowserTaskService、IlinkReplyProcessor、普通 ChatClient、微信回复链、数据库、配置或长期记忆逻辑。
MCP 浏览器操作能力第六阶段：网址输入与公开读取边界优化记录：
1. 用户只发送明确 http 或 https 公开网址时，DeepSeekIntentRouter 即使模型返回 TEXT 或无法解析模型结果，也会路由为 BROWSER_TASK；浏览器服务不会立即启动 MCP，而是提示用户补充读取、总结、查找、筛选或点击等操作。
2. 网址提取在网址后紧跟中文任务描述时于中文边界结束，例如“https://interview.javaguide.cn/帮我总结一下”会访问 https://interview.javaguide.cn/，并将后续中文识别为用户动作。
3. BrowserTaskService 的系统提示词明确：仅可读取无需登录即可见的公开标题、正文、列表、公告、日期、作者和公开元数据；允许在明确请求下点击、筛选、翻页、查找、摘要、提取、比较和整理公开内容。
4. 系统提示词同时明确禁止读取登录、付费墙、验证码、短信验证或扫码验证之后的内容，禁止读取账号密码、Cookie、Token、个人资料、订单、私信或其他私人数据，禁止下载文件。
5. 已新增裸网址补动作、网址后中文动作提取和 URL 路由回退的单元测试；执行 mvn -q -DskipTests compile 及浏览器、路由、微信分流定向测试均通过。
AI 助手能力说明提示词优化记录：
1. 普通聊天系统提示词已集中声明当前项目能力：实时新闻与天气、时间日期、数学计算、翻译、PDF/DOCX/XLSX 文件生成与微信发送、文生图、参考图编辑、图片识别、语音回复、长期记忆管理，以及用户提供明确公开网址和动作时的真实浏览器操作。
2. 已明确浏览器仅处理公开网页；登录、验证码、支付、购买、发布、删除、上传、下载和私人数据读取均属于禁止边界。
3. 已明确聊天历史中“不能生成文件”“没有网页浏览工具”等旧回答是过期错误信息，模型不得复述或据此限制当前能力。
4. 未修改 MCP 浏览器会话、ChatMemory 写入、SQLite、长期记忆、路由规则、文件工具或安全工具白名单。
5. 已新增 AiChatServiceImplCapabilityInstructionsTest，并执行 mvn -q -DskipTests compile 及能力提示词、文件格式、浏览器路由定向测试通过。
AI 助手能力说明提示词强化记录：
1. 微信实测发现模型曾因追求简短而仅列出基础工具，遗漏文件、图片、语音、记忆和公开网页能力；本次将能力询问改为必须逐项列出八类能力的固定提示词，不允许为简短省略。
2. 已明确即使普通 ChatClient 当前未直接挂载文件、图片、语音或浏览器工具，也不得推断系统没有这些能力；这些能力由外层意图分流和微信发送链路执行。
3. 已禁止能力回答根据用户历史偏好作出无关承诺，避免把“能做什么”误答成特定行程或未完成事项。
4. 仅修改 AiChatServiceImpl 的能力提示词及其单测；未修改 MCP 会话、ChatMemory、SQLite、长期记忆、路由、文件工具或浏览器安全边界。
5. 已执行 mvn -q -DskipTests compile 及 AiChatServiceImplCapabilityInstructionsTest、DeepSeekIntentRouterTest、FileGenerationToolTypeTest 通过。

????????????????
1. ??? Spring AI 1.1.2 ? Prompt.augmentSystemMessage(String) ???? SystemMessage ??????????????????????????????
2. LongTermMemoryAdvisor ??????? SystemMessage ?? long-term-memory ???????????????
3. ????? / ????? / ????? / ??????????????????????????????????????
4. ?? LongTermMemoryAdvisorSystemPromptTest??????????????????????????
5. ??? mvn -q '-Dtest=LongTermMemoryAdvisorTest,LongTermMemoryAdvisorSystemPromptTest' test ? mvn -q -DskipTests compile?????

验收收敛期协作标准（用户确认）：
1. 三天是项目收尾期限，不再新增功能、不做架构大改；所有改动以保持既有功能和对外结果完全不变为前提。
2. 必须覆盖并梳理全部非测试 Java 类；每个类均需说明所属链路、入口/调用方、职责、关键方法、依赖、输出和异常边界。
3. 每次梳理某条链路前，先结合全项目的 Spring 注入、配置、异步时序、调用关系、测试和 Git 历史进行核对，不得孤立判断。
4. 只有能够以代码、配置和测试证明“删除或简化后功能、异常处理、时序和对外结果均不变”的项，才可列为候选；存在任何不确定性则保留，且不列为可删。
5. 不得仅因代码较多、写法复杂或文本引用较少而删除或合并；框架扫描、接口实现、配置绑定、反射和外部 HTTP 入口必须单独核实。
6. 每次提出改动前，先说明目的、影响范围、验证方式和准确文件；经用户批准后再修改。改动后执行相应测试、编译和差异检查。
7. 使用 docs/验收期链路梳理台账.md 持续记录覆盖进度、证据、不可动结论、候选项和验证结果，便于用户学习和跨对话延续。

面试准备长期记忆（2026-08-04 用户要求）：
1. 用户在准备"微信端多模态 AI 助手项目"（本仓库）的面试；完整面试问题总库见 docs/面试准备/微信端多模态AI助手项目面试问题总库.md，包含 15 个模块和压迫式追问，原样保存未删改。
2. 协作流程约定：先吸收对话目的 → 把用户要求和全部问题设为长期记忆 → 向用户确认理解完成 → 用户再下发具体任务。
3. 回答面试问题必须以项目真实代码、配置、测试和 Git 历史为证据；未实现功能如实说明，不得冒充已实现；压力式追问要落到具体类、方法、表、配置键和日志。

面试助手工作模式（2026-08-04 用户确认，2026-08-05 更正，默认执行）：
1. 角色：用户专属面试助手，基于 ykd-project 真实代码串讲"微信端多模态 AI 助手项目"；讲解依据 = 项目最基础的完整真实代码（含配置/测试/Git/日志）+ 网上同类项目面试拷打环节有参考价值的标准答案模式。
2. 15 个模块的问题 = 用户简历内容扩展出的可能面试题；按"消息流逻辑"顺序逐模块串讲：iLink(模块2)→意图路由(3)→工具链(4)→实时信息(5)→图片/语音/文件(9)→长期记忆(7)→Outbox(8)→浏览器(6)→数据库(10)→定时任务(11，未实现只讲设计)→安全(12)→测试(13)→协作(14)→最后整体串讲(1+15)。
3. 每个模块必须讲到全部相关代码的每一行（除 import 外），讲清每行作用与设计原因，禁止泛泛而谈；模块内代码多则分多批。
4. 讲解过程中，每讲完一段足以回答某些问题的代码，就立即把问题总库中对应的问题列出（标注模块号+题号），只列不给答案；用户先自答，之后向我要答案，我再按"已讲代码 + 真实项目证据 + 网上有参考价值面经"回答。
5. 模块 1 的整体性/真实性大问题（架构图、真实调用、项目归属、并发串号等）留到全部模块串完、整体链路串通后统一列。
6. 节奏：一次只串一个模块，模块内分批推进；每批代码讲完立即检查并列出该批能回答的问题（模块号+题号）；用户中途要答案只回答已讲过代码覆盖的问题。
7. 答案标准：已讲代码 → 真实项目证据 → 网上同类面经；未实现功能如实说明，禁止编造。

面试官模拟定位（2026-08-05 用户要求，与代码串讲并行）：
1. 在"代码串讲"之外，用户需要我扮演中大厂面试官，基于【用户真实简历 + 项目真实代码 + 最新高时效面经】对项目进行全方位无死角拷打提问。
2. 提问标准：问题必须贴近真实项目拷打场景、有含金量与参考价值；优先从简历声称的能力/模块切入；追问真实性（具体类、方法、表、配置、日志、踩坑过程、上线/联调证据）。
3. 客观判断原则：不完全听用户"这种问题不会问"；真实面试中类级问题以"真实性核验"形式出现（例如压迫式追问"说出你深度参与的三个核心类及关键方法"），因此保留但按面试官口吻改写，而不是死板地问"这个类是干什么的"。
4. 目标：用户把我问的问题全部搞懂后，真实面试能对答如流、提高成功率。

答题格式约定（2026-08-06 用户要求，2026-08-08 补充）：回答问题时先答大问题（外层完整总体回答），再按子题号（如 2.1、7.3）逐条分开回答分问题。大问题是答案主体，必须相对详细地讲清背景、设计、真实执行链路、关键代码证据、取舍和边界，并形成一段面试时能够直接讲述的完整回答；小问题只提炼和补充对应结论，保持精简、清楚，避免把大问题内容机械重复多遍。每题正式回答前按“当前真实项目代码/配置/测试/Git/日志 → 用户最新版简历对应表述 → 网上同类问题有参考价值的回答方式”三层核对，最终以项目事实和用户真实经历为准；未实现功能如实标注，网上答案不得覆盖项目事实。框架机制类问题（如工具调用、MCP、Advisor）按'框架如何识别能力 → 处理链路'展开讲，用真实面试口语，重点讲清识别原理与流转过程，不只罗列项目类名。

最新版简历答题约束（2026-08-08 用户提供）：
1. 简历基线文件为 `C:/Users/Lenovo/Desktop/李琛阳_一页宽版简历_最新版_预览.png`。涉及个人背景、负责范围、组长角色、项目指标或技术亮点时，回答前必须核对该版本简历，不得擅自扩大职责或编造上线数据。
2. 与本仓库直接相关的简历经历为“优课达（杭州）网络有限公司，Agent 开发实习生（实习小组组长），2026-06 至 2026-08”，项目为“微信端多模态 AI 助手”。简历重点声明五类贡献：ReAct 工具编排、MCP 浏览器任务与安全控制、个性化长期记忆与分层召回、回复/记忆/索引异步解耦、团队协作与交付。
3. 面试答案既要能支撑简历中的 Spring AI Tool Calling、Playwright MCP、SQLite + Redis Stack、Outbox、异步链路等关键词，也要主动说明真实边界；定时任务和 iLink session-file 恢复等未实现能力不得说成已交付。

面试题库与已答进度最新记忆（2026-08-08 用户确认，优先于旧题号记录）：
1. 标准题库固定为 docs/面试准备/微信端多模态AI助手项目面试问题总库.md 中的 11 轮、72 道主问题及其子题、考察点；旧记录中的“15 个模块”只用于代码串讲业务分组，不用于题号。
2. 已完整回答的主问题为第 2、5、7-28、37 题（其中第 6 题未回答）；后续串讲列题时跳过这些已答题，除非用户明确要求复习、纠错或重答。当前未答主问题为第 1、3、4、6、29-36、38-72 题。
3. 用户提供的历史答案可以作为面试口语、层次和思路参考，但正式答案必须重新核对当前代码、配置、测试、Git 与日志；历史答案和摘要不得覆盖真实实现。
4. 用户特别指定工具调用开头两题为答题基准。工具调用问题必须按“@Tool/@ToolParam 声明 → Spring AI 生成 ToolCallback、ToolDefinition 与参数 JSON Schema → 模型返回工具名和参数 JSON → ToolCallingManager 匹配并执行 Java 方法 → 工具结果回传模型 → ToolCallAdvisor/ReActTraceAdvisor 驱动循环直至最终回答”展开，先说明框架如何识别能力，再说明处理链路，并强调“模型决策、框架执行”。
5. 第 21 题回答要落实到 AiChatServiceImpl、SpringAiChatConfig、主 ChatClient、ReActTraceAdvisor/ToolCallAdvisor、ToolCallingManager 和五个工具 Bean；第 22 题要先解释 ToolCallback 是模型可见定义与框架可执行回调的桥梁，再区分普通 Service 方法，并明确模型不直接执行 Java 代码。
6. 后续代码串讲输出风格固定参考用户附件：当前模块和批次 → 按类展示带行内注释的真实代码 → 紧跟详细讲解（作用、设计原因、调用方、异常去向、并发时序、边界与追问）→ 真实请求手动跑流程 → 按标准题号只列尚未回答的问题。代码过多可分批，但仍须覆盖全部相关代码（除 import 外），不能用少量摘录代替完整覆盖。
