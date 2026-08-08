# 猎聘 MCP 调用答辩知识点全攻略

---

## 一、MCP 协议基础

### 1.1 什么是 MCP

- **Model Context Protocol**（模型上下文协议），由 Anthropic 提出的开放标准
- 让 AI 模型能够与外部工具/数据源进行标准化通信
- 类比：MCP 之于 AI 工具 = USB 之于外设（统一接口标准）

### 1.2 MCP 核心架构

```
┌─────────────┐     MCP协议      ┌─────────────┐
│  MCP Client │ ◄──────────────► │  MCP Server │
│  (AI模型端)  │    JSON-RPC      │  (工具端)    │
└─────────────┘                  └─────────────┘
```

### 1.3 MCP 传输方式

| 传输方式 | 特点 | 适用场景 |
|---------|------|---------|
| **stdio** | 标准输入输出，进程内通信 | 本地工具、CLI集成 |
| **SSE** | Server-Sent Events，HTTP长连接 | Web应用、远程服务 |
| **Streamable HTTP** | 新标准，支持流式 | 生产环境推荐 |

### 1.4 MCP 核心概念

- **Tools**：模型可调用的函数（本项目核心）
- **Resources**：模型可读取的数据源
- **Prompts**：预定义的提示模板
- **Sampling**：服务器请求模型推理

---

## 二、Spring AI 与 MCP 集成

### 2.1 技术栈选型

```
Spring Boot 3.5.15
Spring AI 1.1.2
Spring AI Alibaba 1.1.2.2
Java 21
```

### 2.2 关键依赖

```xml
<!-- MCP Server 端 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>

<!-- MCP Client 端（备用） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client-webflux</artifactId>
</dependency>
```

### 2.3 配置要点

```properties
# MCP Server 配置
spring.ai.mcp.server.enabled=true
spring.ai.mcp.server.name=ykd-mcp-server
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.transport=sse

# MCP Client 配置（当前禁用）
spring.ai.mcp.client.enabled=false
```

**答辩要点**：解释为什么同时配置了 Server 和 Client——Server 暴露工具给外部调用，Client 预留连接外部 MCP Server 的能力。

---

## 三、项目架构设计

### 3.1 模块划分

```
ykd-project/
├── ykd-liepin-lib/        # 猎聘核心库（可复用）
│   ├── client/            # Playwright 浏览器自动化
│   ├── config/            # 配置类
│   └── tool/              # MCP 工具定义
│
└── ykd-project-app/       # 主应用
    ├── ai/config/         # MCP 注册配置
    ├── ai/service/        # 业务逻辑
    ├── ai/trace/          # ReAct 追踪
    ├── ai/routing/        # 意图路由
    └── ai/utils/          # 其他工具实现
```

### 3.2 核心调用链路

```
用户消息
    ↓
AiChatController（REST API）
    ↓
AiChatServiceImpl
    ├── 恢复聊天记忆（SQLite → ChatMemory）
    ├── RAG 向量检索
    ├── 构建系统提示（含工具使用规则）
    ↓
Spring AI ChatClient
    ├── Advisor: 聊天记忆注入
    ├── Advisor: ReAct 追踪
    ├── ToolCallingManager: MCP 工具调用管理
    ↓
AI 模型（DeepSeek）返回 tool_calls
    ↓
框架自动执行工具调用
    ↓
工具结果 → 模型继续推理 → 最终回答
```

---

## 四、猎聘工具实现细节

### 4.1 工具定义（LiepinApplyTool.java）

使用 `@Tool` 注解定义两个核心方法：

```java
@Tool(name = "search_liepin_jobs", description = "搜索猎聘岗位")
public String searchJobs(
    @ToolParam(description = "搜索关键词") String keyword,
    @ToolParam(description = "城市") String city,
    @ToolParam(description = "薪资范围") String salary,
    @ToolParam(description = "工作经验") String experience
) { ... }

@Tool(name = "apply_liepin_jobs", description = "投递猎聘岗位")
public String applyJobs(
    @ToolParam(description = "岗位序号或URL") String jobUrlsOrIndices
) { ... }
```

**设计亮点**：

- `lastSearchResults` 使用 `volatile` 保证线程可见性
- 支持"搜索→选择→投递"的多轮交互流程
- 智能区分"可投递"和"仅聊一聊"岗位

### 4.2 浏览器自动化（LiepinClient.java）

使用 **Playwright 1.44.0** 实现：

```java
// 会话管理
- login(): synchronized 方法，启动 Chromium，注入 Cookie
- injectCookies(): 解析 Cookie 字符串注入浏览器上下文
- checkLoginStatus(): 检测用户头像判断登录状态

// 岗位搜索
- searchJobs(): 导航到搜索页，解析岗位列表
- buildSearchUrl(): 构造搜索 URL，支持 30+ 城市代码映射
- parseJobList(): 双重解析策略（属性定位 + 类名兜底）
- checkJobCanApply(): 打开详情页判断投递类型

// 简历投递
- applyJob(): 导航→检查→点击→处理弹窗→验证结果
- handleApplyDialog(): 处理三类弹窗
- checkApplyResult(): 多维度判断投递是否成功
```

### 4.3 反爬虫策略

```java
// 随机延迟
private void randomDelay() {
    Thread.sleep(500 + random.nextInt(1500)); // 500ms-2000ms
}

// User-Agent 伪装
"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36
 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

// 批量投递间隔
每个岗位之间有随机延迟
```

---

## 五、工具注册与管理

### 5.1 McpServerConfig 配置

```java
@Configuration
public class McpServerConfig {
    @Bean
    public ToolCallbackProvider toolCallbackProvider(
            LiepinApplyTool liepinApplyTool,
            BaiduSearchTool baiduSearchTool,
            WeatherTool weatherTool,
            // ... 其他工具
    ) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(liepinApplyTool, baiduSearchTool, weatherTool, ...)
            .build();
    }
}
```

### 5.2 工具列表（共 9 个）

| 工具名 | 功能 | 文件 |
|-------|------|------|
| `search_liepin_jobs` | 搜索猎聘岗位 | LiepinApplyTool |
| `apply_liepin_jobs` | 投递猎聘岗位 | LiepinApplyTool |
| `query_current_weather` | 查询天气 | WeatherTool |
| `search_realtime_information` | 实时搜索 | BaiduSearchTool |
| `translate_text` | 翻译 | TranslationTool |
| `calculate_math_expression` | 计算 | MathCalculatorTool |
| `get_time_info` | 获取时间 | TimeTool |
| `schedule_task` | 定时任务 | ScheduledTaskTool |
| `send_email` | 发送邮件 | EmailTool |
| `generate_qr_code` | 生成二维码 | QrCodeTool |

---

## 六、ReAct 循环与追踪

### 6.1 ReAct 模式

```
Reasoning（推理）→ Action（行动）→ Observation（观察）→ 循环
```

### 6.2 ReActTraceAdvisor 实现

```java
public class ReActTraceAdvisor extends ToolCallAdvisor {
    @Override
    protected void doBeforeCall(ToolCall toolCall) {
        // 记录工具调用前的状态
    }

    @Override
    protected void doAfterCall(ToolCall toolCall, ToolCallResult result) {
        // 记录模型思考过程（DeepSeek reasoning content）
        // 记录工具调用动作
        // 限制最大 8 轮工具调用
    }

    @Override
    protected void doFinalizeLoop() {
        // 输出统计：步骤数、工具轮数、token 用量、耗时
    }
}
```

### 6.3 工具调用约束（TOOL_USAGE_INSTRUCTIONS）

```java
private static final String TOOL_USAGE_INSTRUCTIONS = """
    1. 天气查询必须本轮重新调用，不得用记忆中的旧结果
    2. 新闻必须调用实时搜索工具，不得用训练数据编造
    3. 翻译必须调用 translate_text，不得模型自行翻译
    4. 计算必须调用 calculate_math_expression
    5. 猎聘求职必须先搜索再确认投递，不得自动投递
    """;
```

---

## 七、意图路由机制

### 7.1 DeepSeekIntentRouter

两级路由策略：

```java
// 1. 本地规则匹配（快速）
private UserIntent matchExplicitIntent(String message) {
    // 正则匹配明确意图
    Pattern JOB_APPLY_PATTERN = Pattern.compile("猎聘|求职|找工作|投简历");
    if (JOB_APPLY_PATTERN.matcher(message).find()) {
        return UserIntent.TEXT; // 由 LiepinApplyTool 处理
    }
    // ... 其他规则
}

// 2. DeepSeek 模型路由（复杂场景）
public UserIntent route(String message, List<Message> history) {
    // 先尝试本地规则
    UserIntent localResult = matchExplicitIntent(message);
    if (localResult != null) return localResult;

    // 调用 DeepSeek 进行意图分类
    return deepSeekRoute(message, history);
}
```

---

## 八、数据持久化

### 8.1 聊天记忆存储

```java
// SQLite 持久化
@Component
public class SqliteChatMessageRepository {
    // 保存消息到 SQLite
    public void save(String conversationId, Message message);

    // 恢复历史消息
    public List<Message> findByConversationId(String conversationId);

    // 清理超过 100 条的旧消息
    public void softDeleteOldMessages(String conversationId, int keepCount);
}
```

### 8.2 向量存储（RAG）

```java
@Bean
public SQLiteVectorStore sqliteVectorStore(EmbeddingModel embeddingModel) {
    return SQLiteVectorStore.builder()
        .embeddingModel(embeddingModel)
        .build();
}
```

---

## 九、答辩高频问题准备

### Q1: 为什么选择 MCP 协议？

**答**：

1. **标准化**：统一的工具调用接口，不依赖特定 AI 平台
2. **可扩展**：新增工具只需实现 `@Tool` 注解，无需修改调用逻辑
3. **解耦**：工具实现与 AI 模型完全分离
4. **生态兼容**：支持所有 MCP 兼容的 AI 客户端

### Q2: 为什么用 Playwright 而不是直接调用猎聘 API？

**答**：

1. 猎聘没有公开的投递 API
2. 需要模拟真实浏览器行为（登录、点击、弹窗处理）
3. Playwright 支持多浏览器（Chromium/Firefox/WebKit）
4. 内置反检测能力，降低封号风险

### Q3: 如何保证 Cookie 的有效性？

**答**：

1. 配置文件注入初始 Cookie
2. `checkLoginStatus()` 检测登录状态
3. 检测到重定向登录页时标记 Cookie 失效
4. 提示用户重新配置 Cookie

### Q4: 如何处理猎聘的反爬虫机制？

**答**：

1. 随机延迟（500ms-2000ms）
2. User-Agent 伪装
3. 批量操作间隔
4. 模拟真实用户行为模式

### Q5: ReAct 循环的作用是什么？

**答**：

1. **Reasoning**：模型分析用户意图，决定调用哪个工具
2. **Action**：框架执行工具调用
3. **Observation**：模型获取工具结果，继续推理
4. 支持多轮工具调用（最多 8 轮），实现复杂任务

### Q6: 为什么需要 McpToolCallingManager？

**答**：

1. 包装默认的 `DefaultToolCallingManager`
2. 增加猎聘工具的 MCP 调用拦截逻辑
3. 实现工具调用的前置/后置处理
4. 统一管理所有工具的调用流程

### Q7: 如何保证线程安全？

**答**：

1. `lastSearchResults` 使用 `volatile` 关键字
2. `login()` 方法使用 `synchronized`
3. SQLite 操作使用事务
4. 每个用户会话独立的 `conversationId`

### Q8: 项目如何扩展新的 MCP 工具？

**答**：

1. 创建新的 Tool 类，使用 `@Tool` 注解定义方法
2. 在 `McpServerConfig` 中注册到 `ToolCallbackProvider`
3. 在 `TOOL_USAGE_INSTRUCTIONS` 中添加使用规则（可选）
4. 重启应用即可生效

---

## 十、技术亮点总结

| 亮点 | 说明 |
|-----|------|
| **MCP 协议标准化** | 统一工具调用接口，易于扩展 |
| **Playwright 自动化** | 真实浏览器行为，反检测能力强 |
| **ReAct 循环追踪** | 完整记录 AI 推理过程，便于调试 |
| **意图路由** | 本地规则 + 模型路由，响应速度快 |
| **多轮交互** | 搜索→选择→投递，用户体验好 |
| **线程安全** | volatile + synchronized，保证并发正确性 |
| **配置外部化** | Cookie、超时等参数可配置 |
| **模块化设计** | liepin-lib 可独立复用 |

---

## 十一、可能的改进方向（加分项）

1. **Cookie 自动刷新**：集成 OAuth 或扫码登录，自动更新 Cookie
2. **分布式支持**：使用 Redis 存储搜索结果，支持多实例部署
3. **岗位匹配算法**：基于用户简历智能推荐岗位
4. **投递结果追踪**：记录投递历史，统计面试邀请率
5. **多平台支持**：扩展到 Boss 直聘、拉勾等平台
6. **A2A 协议集成**：与其他 Agent 协作完成复杂求职任务

---

## 附录：关键代码文件索引

| 文件 | 路径 | 说明 |
|-----|------|------|
| LiepinApplyTool.java | ykd-liepin-lib/src/main/java/com/fourth/ykd/ilink/tool/ | MCP 工具定义 |
| LiepinClient.java | ykd-liepin-lib/src/main/java/com/fourth/ykd/ilink/client/ | Playwright 自动化 |
| LiepinProperties.java | ykd-liepin-lib/src/main/java/com/fourth/ykd/ilink/config/ | 配置属性 |
| McpServerConfig.java | ykd-project-app/src/main/java/com/fourth/ykd/ai/config/ | 工具注册 |
| SpringAiChatConfig.java | ykd-project-app/src/main/java/com/fourth/ykd/ai/config/ | ChatClient 配置 |
| AiChatServiceImpl.java | ykd-project-app/src/main/java/com/fourth/ykd/ai/service/impl/ | 核心业务逻辑 |
| ReActTraceAdvisor.java | ykd-project-app/src/main/java/com/fourth/ykd/ai/trace/ | ReAct 追踪 |
| DeepSeekIntentRouter.java | ykd-project-app/src/main/java/com/fourth/ykd/ai/routing/ | 意图路由 |
| application.properties | ykd-project-app/src/main/resources/ | 配置文件 |

---

**祝答辩顺利！** 🎯