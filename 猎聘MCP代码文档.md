# 猎聘MCP简历投递系统 - 代码文档

## 📋 目录
1. [项目概述](#1-项目概述)
2. [系统架构](#2-系统架构)
3. [核心组件详解](#3-核心组件详解)
4. [代码流程分析](#4-代码流程分析)
5. [配置说明](#5-配置说明)
6. [关键技术点](#6-关键技术点)
7. [模拟答辩问题与答案](#7-模拟答辩问题与答案)

---

## 1. 项目概述

### 1.1 功能简介
本系统是一个基于 **MCP（Model Context Protocol）协议** 的智能简历投递系统，实现了：
- 🔍 **智能岗位搜索**：通过AI对话搜索猎聘网岗位
- 📄 **自动简历投递**：一键批量投递简历
- 🤖 **AI驱动交互**：用户通过自然语言与AI对话完成操作
- 🔄 **MCP协议集成**：工具通过MCP协议暴露给AI模型调用

### 1.2 技术栈
| 技术 | 用途 |
|------|------|
| Java 17+ | 主要开发语言 |
| Spring Boot | 应用框架 |
| Spring AI | AI集成框架 |
| Playwright | 浏览器自动化 |
| MCP协议 | AI工具调用协议 |
| SQLite | 本地数据存储 |

---

## 2. 系统架构

### 2.1 架构图
```
┌─────────────────────────────────────────────────────────────┐
│                      用户界面层                              │
│                   (AI Chat Controller)                       │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                      AI服务层                                │
│              (AiChatService + DeepSeek)                      │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                   MCP工具调用层                              │
│            (McpToolService + McpToolCallingManager)          │
└────────────────────────────┬────────────────────────────────┘
                             │
              ┌──────────────┴──────────────┐
              ▼                             ▼
┌──────────────────────┐      ┌──────────────────────┐
│   猎聘工具层          │      │   其他工具层          │
│  (LiepinApplyTool)   │      │  (天气/搜索/邮件等)   │
└──────────┬───────────┘      └──────────────────────┘
           │
           ▼
┌──────────────────────┐
│   浏览器自动化层      │
│   (LiepinClient)     │
│   Playwright实现      │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│     猎聘网站          │
│   (liepin.com)       │
└──────────────────────┘
```

### 2.2 模块划分
```
ykd-project/
├── ykd-liepin-lib/              # 猎聘核心库
│   ├── client/
│   │   └── LiepinClient.java    # 浏览器自动化客户端
│   ├── config/
│   │   ├── LiepinConfig.java    # 配置类
│   │   └── LiepinProperties.java # 配置属性
│   └── tool/
│       └── LiepinApplyTool.java # MCP工具定义
│
└── ykd-project-app/             # 主应用
    ├── config/
    │   └── McpServerConfig.java # MCP服务器配置
    └── service/
        ├── McpToolService.java      # MCP工具服务
        └── McpToolCallingManager.java # 工具调用管理
```

---

## 3. 核心组件详解

### 3.1 LiepinClient - 浏览器自动化客户端

**文件位置**：`ykd-liepin-lib/src/main/java/com/fourth/ykd/ilink/client/LiepinClient.java`

**核心职责**：
- 使用Playwright控制浏览器
- Cookie登录猎聘网
- 搜索岗位信息
- 自动投递简历

**关键方法**：

```java
// 1. 登录方法 - 使用Cookie注入方式
public synchronized boolean login()

// 2. 搜索岗位
public List<JobInfo> searchJobs(String keyword, String city, String salary, String experience)

// 3. 投递单个岗位
public ApplyResult applyJob(String jobUrl)

// 4. 批量投递
public List<ApplyResult> applyJobs(List<String> jobUrls)
```

**数据结构**：
```java
// 岗位信息
public record JobInfo(
    String title,        // 岗位标题
    String company,      // 公司名称
    String salary,       // 薪资范围
    String city,         // 城市
    String experience,   // 经验要求
    String url,          // 岗位链接
    boolean canApply     // 是否可直接投递
) {}

// 投递结果
public record ApplyResult(
    String jobTitle,     // 岗位标题
    boolean success,     // 是否成功
    String message       // 结果消息
) {}
```

### 3.2 LiepinApplyTool - MCP工具定义

**文件位置**：`ykd-liepin-lib/src/main/java/com/fourth/ykd/ilink/tool/LiepinApplyTool.java`

**核心职责**：
- 定义AI可调用的工具方法
- 处理搜索和投递逻辑
- 缓存搜索结果支持序号投递

**工具定义**：

```java
@Tool(name = "search_liepin_jobs", description = "在猎聘网上搜索岗位信息...")
public String searchJobs(String keyword, String city, String salary, String experience)

@Tool(name = "apply_liepin_jobs", description = "在猎聘网上投递简历...")
public String applyJobs(String jobUrlsOrIndices)
```

**设计亮点**：
1. **结果缓存**：使用`lastSearchResults`缓存最近搜索结果
2. **序号投递**：用户可输入"1,3,5"或"全部"进行批量操作
3. **智能分类**：区分"可投递"和"仅聊一聊"两种岗位类型

### 3.3 McpServerConfig - MCP服务器配置

**文件位置**：`ykd-project-app/src/main/java/com/fourth/ykd/ai/config/McpServerConfig.java`

**核心职责**：
- 注册所有MCP工具
- 配置工具回调提供者

```java
@Configuration
public class McpServerConfig {
    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(...) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(
                    baiduSearchTool,
                    weatherTool,
                    // ... 其他工具
                    liepinApplyTool  // 猎聘工具
                )
                .build();
    }
}
```

### 3.4 McpToolService - MCP工具服务

**文件位置**：`ykd-project-app/src/main/java/com/fourth/ykd/ai/service/McpToolService.java`

**核心职责**：
- 封装工具调用逻辑
- 提供工具信息查询
- 构建猎聘工具描述

**关键方法**：
```java
// 获取所有可用工具
public List<ToolInfo> getAvailableTools()

// 调用指定工具
public String callTool(String toolName, String argsJson)

// 构建猎聘工具描述（注入系统提示）
public String buildLiepinToolDescriptions()
```

---

## 4. 代码流程分析

### 4.1 搜索岗位流程
```
用户输入："帮我搜索北京的Java开发岗位，薪资20-30k"
    │
    ▼
AI模型解析意图，调用 search_liepin_jobs 工具
    │
    ▼
McpToolService.callTool("search_liepin_jobs", args)
    │
    ▼
LiepinApplyTool.searchJobs("Java开发", "北京", "20-30k", "")
    │
    ▼
LiepinClient.searchJobs()
    ├── 1. login() - Cookie登录
    ├── 2. buildSearchUrl() - 构建搜索URL
    ├── 3. page.navigate() - 访问搜索页
    ├── 4. parseJobList() - 解析岗位列表
    └── 5. checkJobCanApply() - 检查投递类型
    │
    ▼
返回格式化的岗位列表给AI
    │
    ▼
AI展示结果给用户
```

### 4.2 投递简历流程
```
用户输入："投递第1、3、5个岗位"
    │
    ▼
AI模型调用 apply_liepin_jobs 工具
    │
    ▼
LiepinApplyTool.applyJobs("1,3,5")
    │
    ▼
resolveUrlsByType() - 解析序号为URL
    ├── 从lastSearchResults获取缓存
    ├── 区分可投递和仅聊一聊
    └── 分类处理
    │
    ▼
LiepinClient.applyJobs(urls)
    │
    ├── 遍历每个URL
    │   └── applyJob(url)
    │       ├── 1. navigate(jobUrl) - 访问岗位页
    │       ├── 2. 查找投递按钮
    │       ├── 3. click() - 点击投递
    │       ├── 4. handleApplyDialog() - 处理弹窗
    │       └── 5. checkApplyResult() - 检查结果
    │
    ▼
返回投递结果汇总
```

### 4.3 MCP调用链路
```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  AI模型      │────▶│  Spring AI  │────▶│  MCP协议层   │
│  (DeepSeek) │     │  Framework  │     │             │
└─────────────┘     └─────────────┘     └──────┬──────┘
                                               │
                                               ▼
                                        ┌─────────────┐
                                        │ McpToolService│
                                        └──────┬──────┘
                                               │
                                               ▼
                                        ┌─────────────┐
                                        │LiepinApplyTool│
                                        └──────┬──────┘
                                               │
                                               ▼
                                        ┌─────────────┐
                                        │ LiepinClient │
                                        │ (Playwright) │
                                        └──────┬──────┘
                                               │
                                               ▼
                                        ┌─────────────┐
                                        │  猎聘网站    │
                                        └─────────────┘
```

---

## 5. 配置说明

### 5.1 猎聘配置
在 `application.properties` 中配置：

```properties
# ==================== 猎聘 ====================
# Cookie从浏览器获取：登录liepin.com → F12 → Console → document.cookie → 复制
liepin.cookie=${LIEPIN_COOKIE:}
liepin.headless=${LIEPIN_HEADLESS:false}
liepin.timeout-ms=${LIEPIN_TIMEOUT_MS:15000}
liepin.delay-min-ms=${LIEPIN_DELAY_MIN_MS:500}
liepin.delay-max-ms=${LIEPIN_DELAY_MAX_MS:2000}
liepin.default-city=${LIEPIN_DEFAULT_CITY:}
liepin.default-keyword=${LIEPIN_DEFAULT_KEYWORD:}
```

### 5.2 MCP服务器配置
```properties
# ==================== MCP Server ====================
spring.ai.mcp.server.enabled=true
spring.ai.mcp.server.name=ykd-mcp-server
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.transport=sse
```

### 5.3 Cookie获取方法
1. 打开浏览器，登录 `liepin.com`
2. 按 `F12` 打开开发者工具
3. 切换到 `Console` 标签
4. 输入 `document.cookie` 并回车
5. 复制输出的Cookie字符串
6. 粘贴到配置文件的 `liepin.cookie` 属性

---

## 6. 关键技术点

### 6.1 MCP协议
**MCP（Model Context Protocol）** 是一个开放协议，用于标准化应用程序向LLM提供上下文的方式。

**核心概念**：
- **Tool**：AI可调用的函数/方法
- **ToolCallback**：工具调用的回调接口
- **ToolDefinition**：工具的元数据定义

**本项目中的应用**：
```java
@Tool(name = "search_liepin_jobs", description = "...")
public String searchJobs(...) {
    // 工具实现
}
```

### 6.2 Playwright浏览器自动化
**Playwright** 是微软开源的浏览器自动化库，支持Chrome、Firefox、Safari。

**核心API**：
```java
// 创建浏览器实例
Playwright playwright = Playwright.create();
Browser browser = playwright.chromium().launch();

// 创建页面
Page page = context.newPage();

// 导航
page.navigate("https://www.liepin.com");

// 查找元素
Locator locator = page.locator("selector");

// 点击
locator.click();

// 输入文本
locator.fill("text");
```

### 6.3 Cookie登录机制
**原理**：通过注入Cookie绕过登录流程

**实现步骤**：
1. 解析Cookie字符串为键值对
2. 创建Cookie对象，设置domain和path
3. 注入到浏览器上下文
4. 刷新页面使Cookie生效

```java
private void injectCookies(String cookieStr) {
    List<Cookie> cookies = new ArrayList<>();
    for (String pair : cookieStr.split(";")) {
        String[] parts = pair.split("=", 2);
        cookies.add(new Cookie(parts[0].trim(), parts[1].trim())
                .setDomain(".liepin.com")
                .setPath("/"));
    }
    context.addCookies(cookies);
}
```

### 6.4 反爬虫策略
**随机延迟**：模拟人类操作节奏
```java
private void randomDelay() {
    long delay = ThreadLocalRandom.current().nextLong(
            properties.getDelayMinMs(),
            properties.getDelayMaxMs()
    );
    Thread.sleep(delay);
}
```

**User-Agent伪装**：
```java
context = browser.newContext(new Browser.NewContextOptions()
        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)...")
);
```

### 6.5 岗位类型识别
系统能识别两种岗位类型：
1. **可投递**：有"投简历"按钮
2. **仅聊一聊**：只有"聊一聊"按钮

```java
boolean canApply = cardText.contains("投简历") || cardText.contains("立即申请");
boolean chatOnly = cardText.contains("聊一聊") && !canApply;
```

---

## 7. 模拟答辩问题与答案

### 问题1：请介绍一下你的项目是做什么的？

**答案**：
本项目是一个基于MCP协议的智能简历投递系统。用户可以通过自然语言与AI对话，实现猎聘网岗位的自动搜索和简历投递。系统采用Spring AI框架集成DeepSeek大模型，通过MCP协议将猎聘操作封装为AI可调用的工具，使用Playwright实现浏览器自动化。核心功能包括：智能岗位搜索、批量简历投递、岗位类型识别（可投递/仅聊一聊）、投递结果反馈等。

---

### 问题2：什么是MCP协议？你在项目中是如何使用它的？

**答案**：
**MCP（Model Context Protocol）** 是一个开放协议，用于标准化应用程序向LLM提供上下文的方式，特别是工具调用。

在本项目中，MCP的使用方式如下：
1. **工具定义**：使用`@Tool`注解定义猎聘搜索和投递工具
2. **工具注册**：在`McpServerConfig`中通过`MethodToolCallbackProvider`注册所有工具
3. **工具暴露**：配置嵌入式MCP Server，通过SSE传输协议暴露工具
4. **工具调用**：AI模型通过MCP协议调用工具，`McpToolService`负责路由和执行

**优势**：
- 标准化：统一的工具定义和调用接口
- 解耦：工具实现与AI模型分离
- 可扩展：易于添加新工具

---

### 问题3：为什么选择Playwright而不是Selenium？

**答案**：
选择Playwright主要基于以下考虑：

| 特性 | Playwright | Selenium |
|------|------------|----------|
| 速度 | 更快，原生支持异步 | 较慢 |
| 浏览器支持 | Chrome、Firefox、Safari | 主要Chrome |
| API设计 | 现代化，链式调用 | 相对传统 |
| 自动等待 | 内置智能等待 | 需手动配置 |
| 并发 | 原生支持 | 需额外配置 |
| 维护 | 微软积极维护 | 社区维护 |

**本项目中的具体优势**：
1. **自动等待**：`waitForSelector`、`waitForLoadState`等API简化了元素等待逻辑
2. **Cookie管理**：原生支持Cookie注入，实现简单
3. **无头模式**：一行配置切换有头/无头模式
4. **稳定性**：相比Selenium，Playwright在处理动态页面时更稳定

---

### 问题4：你的系统如何处理猎聘的反爬虫机制？

**答案**：
系统采用多种策略应对反爬虫：

1. **Cookie登录**：
   - 使用真实的用户Cookie，避免模拟登录
   - Cookie从浏览器手动获取，保证有效性

2. **随机延迟**：
   ```java
   // 操作间随机延迟500ms-2000ms
   long delay = ThreadLocalRandom.current().nextLong(500, 2000);
   Thread.sleep(delay);
   ```

3. **User-Agent伪装**：
   - 使用真实的Chrome浏览器User-Agent
   - 模拟正常用户的浏览器环境

4. **操作间隔**：
   - 搜索和投递之间有延迟
   - 批量投递时每个岗位之间有间隔

5. **异常处理**：
   - 检测到重定向到登录页时，标记Cookie无效
   - 投递失败时记录日志，不影响后续操作

---

### 问题5：请详细解释一下搜索岗位的代码流程。

**答案**：
搜索岗位的完整流程如下：

```
1. 入口：LiepinApplyTool.searchJobs()
   │
   ▼
2. 参数校验：检查keyword是否为空
   │
   ▼
3. 调用客户端：LiepinClient.searchJobs()
   │
   ▼
4. 登录检查：login()
   ├── 检查是否已登录且页面未关闭
   ├── 获取配置的Cookie
   ├── 启动Playwright浏览器
   ├── 创建浏览器上下文
   ├── 访问猎聘首页
   ├── 注入Cookie
   └── 刷新页面使Cookie生效
   │
   ▼
5. 构建搜索URL：buildSearchUrl()
   ├── 添加关键词参数（key和keyword）
   ├── 添加城市参数（dq和city）
   ├── 添加薪资参数（salary）
   └── 添加经验参数（workYearCode）
   │
   ▼
6. 访问搜索页：page.navigate(searchUrl)
   │
   ▼
7. 检查登录状态：
   ├── 如果URL包含"passport.liepin.com"或"login"
   └── 说明Cookie无效，返回空列表
   │
   ▼
8. 等待岗位列表加载：
   ├── 尝试多个选择器：
   │   - a[data-nick='job-detail-job-info']
   │   - div.job-detail-box
   │   - div.job-list-box
   └── 每个选择器等待5秒
   │
   ▼
9. 解析岗位列表：parseJobList()
   ├── 方案1：通过data-nick属性定位
   │   ├── 获取所有岗位链接
   │   ├── 遍历前15个岗位
   │   ├── 提取标题、公司、薪资、城市
   │   └── 判断是否可投递
   │
   └── 方案2：通过job-detail-box类名定位（兜底）
   │
   ▼
10. 检查投递类型：checkJobCanApply()
    ├── 访问每个岗位的详情页
    ├── 查找"投简历"按钮
    └── 区分"可投递"和"仅聊一聊"
    │
    ▼
11. 返回结果：List<JobInfo>
```

---

### 问题6：你的系统如何区分"可投递"和"仅聊一聊"的岗位？

**答案**：
系统通过两种方式区分岗位类型：

**方式1：列表页快速判断**
```java
// 从岗位卡片的文本内容判断
boolean canApply = cardText.contains("投简历") || 
                   cardText.contains("立即申请") || 
                   cardText.contains("投递简历");
boolean chatOnly = cardText.contains("聊一聊") && !canApply;
```

**方式2：详情页精确判断**
```java
private boolean checkJobCanApply(String jobUrl) {
    // 1. 访问岗位详情页
    Page detailPage = context.newPage();
    detailPage.navigate(jobUrl);
    
    // 2. 查找投递按钮（精确选择器）
    String[] applySelectors = {
        "div[class*='job-info'] button:has-text('投简历')",
        "button[data-nick='apply-btn']",
        "button:has-text('立即申请')",
        // ... 更多选择器
    };
    
    // 3. 查找聊天按钮
    String[] chatSelectors = {
        "button:has-text('聊一聊')",
        "button:has-text('继续聊')",
    };
    
    // 4. 返回判断结果
    // 有投递按钮 → 可投递
    // 只有聊天按钮 → 仅聊一聊
    // 都没有 → 默认可投递
}
```

**用户展示**：
- 可投递岗位：显示为"📌 【可直接投递】"
- 仅聊一聊岗位：显示为"💬 【仅支持聊一聊】"，附带岗位链接

---

### 问题7：请解释一下McpToolCallingManager的作用。

**答案**：
`McpToolCallingManager` 是自定义的工具调用管理器，用于处理猎聘工具的特殊调用逻辑。

**核心作用**：
1. **路由分流**：区分猎聘工具和其他工具的调用
2. **日志记录**：记录猎聘工具的调用参数
3. **委托执行**：最终仍委托给默认管理器执行

**实现逻辑**：
```java
public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
    // 1. 检查是否有猎聘工具调用
    boolean hasLiepin = output.getToolCalls().stream()
            .anyMatch(tc -> tc.name().startsWith("search_liepin") || 
                           tc.name().startsWith("apply_liepin"));
    
    // 2. 如果没有猎聘工具，走默认执行
    if (!hasLiepin) {
        return delegate.executeToolCalls(prompt, chatResponse);
    }
    
    // 3. 有猎聘工具，记录日志
    output.getToolCalls().stream()
            .filter(tc -> tc.name().startsWith("search_liepin") || ...)
            .forEach(tc -> log.info("[MCP][LIEPIN_CALL] tool={}, args={}", 
                     tc.name(), tc.arguments()));
    
    // 4. 仍然走默认执行路径
    return delegate.executeToolCalls(prompt, chatResponse);
}
```

**设计意图**：
- 为猎聘工具添加专门的日志监控
- 便于后续扩展（如添加限流、重试等逻辑）
- 保持与Spring AI框架的兼容性

---

### 问题8：你的系统如何处理投递过程中的弹窗？

**答案**：
投递过程中可能遇到多种弹窗，系统通过`handleApplyDialog()`方法统一处理：

**弹窗类型及处理**：

1. **二次确认弹窗**
   - 特征：包含"投简历"、"立即投递"、"确认投递"按钮
   - 处理：直接点击确认按钮

2. **打招呼语弹窗**
   - 特征：包含输入框和"发送"按钮
   - 处理：使用默认打招呼语，点击发送

3. **选择简历弹窗**
   - 特征：包含简历列表和"确认"按钮
   - 处理：选择默认简历，点击确认

**代码实现**：
```java
private void handleApplyDialog() {
    // 1. 等待弹窗出现（最多3秒）
    page.waitForSelector("div.ant-modal, div[class*='modal']", 
                         new WaitForSelectorOptions().setTimeout(3000));
    
    // 2. 获取弹窗内容
    Locator modal = page.locator("div.ant-modal, div[class*='modal']");
    
    // 3. 检查是否有投递按钮（二次确认）
    Locator applyInModal = modal.locator("button:has-text('投简历')");
    if (applyInModal.count() > 0) {
        applyInModal.first().click();
        return;
    }
    
    // 4. 查找确认/发送按钮
    Locator confirmBtn = modal.locator("button:has-text('确认'), button:has-text('发送')");
    if (confirmBtn.count() > 0) {
        confirmBtn.first().click();
        // 等待弹窗关闭
        page.waitForSelector("div.ant-modal:not(:visible)");
    }
}
```

**异常处理**：
- 弹窗处理过程中的异常被捕获并记录日志
- 不影响后续投递操作

---

### 问题9：你遇到了哪些技术难点？是如何解决的？

**答案**：

**难点1：猎聘页面元素定位困难**
- **问题**：猎聘使用混淆的CSS类名，传统选择器失效
- **解决**：
  1. 使用`data-nick`属性定位（猎聘自定义属性）
  2. 使用语义化选择器（如`button:has-text('投简历')`）
  3. 多选择器兜底策略

**难点2：Cookie登录不稳定**
- **问题**：Cookie过期或被清除导致登录失败
- **解决**：
  1. 登录后检查页面是否重定向到登录页
  2. 搜索前验证登录状态
  3. 提供清晰的错误提示

**难点3：投递结果判断不准确**
- **问题**：投递成功后页面变化不一致
- **解决**：
  1. 多种成功标志检测（按钮文字、提示信息）
  2. 精确查找岗位详情区域的按钮（排除导航栏干扰）
  3. 记录页面文本用于调试

**难点4：批量投递的性能和稳定性**
- **问题**：连续投递容易触发风控
- **解决**：
  1. 随机延迟机制（500ms-2000ms）
  2. 单个投递失败不影响后续操作
  3. 详细的日志记录便于排查

---

### 问题10：如果要扩展支持其他招聘平台（如BOSS直聘），你会如何设计？

**答案**：

**设计方案**：

1. **抽象接口层**
```java
public interface RecruitmentPlatform {
    List<JobInfo> searchJobs(String keyword, String city, String salary, String experience);
    ApplyResult applyJob(String jobUrl);
    List<ApplyResult> applyJobs(List<String> jobUrls);
    boolean isLoggedIn();
    boolean login();
}
```

2. **平台实现层**
```java
@Component
public class LiepinClient implements RecruitmentPlatform {
    // 猎聘实现
}

@Component
public class BossClient implements RecruitmentPlatform {
    // BOSS直聘实现
}
```

3. **工具工厂**
```java
@Component
public class RecruitmentToolFactory {
    private final Map<String, RecruitmentPlatform> platforms;
    
    public RecruitmentPlatform getPlatform(String name) {
        return platforms.get(name);
    }
}
```

4. **统一工具定义**
```java
@Tool(name = "search_jobs", description = "搜索岗位...")
public String searchJobs(String platform, String keyword, ...) {
    RecruitmentPlatform client = factory.getPlatform(platform);
    return client.searchJobs(keyword, ...);
}
```

**优势**：
- 符合开闭原则，易于扩展
- 统一的接口便于维护
- 平台差异封装在实现层

**扩展步骤**：
1. 创建新平台的Client类
2. 实现RecruitmentPlatform接口
3. 注册到工厂
4. 配置新平台的属性

---

## 📝 总结

本系统通过MCP协议实现了AI与招聘平台的智能交互，具有以下特点：

1. **技术先进**：采用MCP协议、Spring AI、Playwright等前沿技术
2. **架构清晰**：分层设计，职责明确
3. **易于扩展**：支持添加新的招聘平台
4. **用户友好**：自然语言交互，操作简单
5. **稳定可靠**：完善的异常处理和日志记录

**核心价值**：
- 将重复性的简历投递工作自动化
- 通过AI提升用户体验
- 为求职者节省大量时间

---

*文档生成时间：2026-08-04*
*作者：王文佳*