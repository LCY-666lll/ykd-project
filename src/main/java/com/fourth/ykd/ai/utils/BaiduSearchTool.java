package com.fourth.ykd.ai.utils;

import com.alibaba.cloud.ai.toolcalling.baidusearch.BaiduSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 百度搜索工具 —— 使用 @Tool 声明式注解，委托给 Spring AI Alibaba 官方百度搜索工具。
 *
 * <p>架构职责：</p>
 * <ul>
 *   <li><b>@Tool 注解</b>：向 AI 模型声明工具描述和参数，DeepSeek 自动决策何时调用</li>
 *   <li><b>BaiduSearchService</b>（官方 Bean）：执行 HTTP 抓取 + JSoup HTML 解析，
 *       无需手写 API 调用代码</li>
 * </ul>
 *
 * <p>Bean 名称解析：</p>
 * <pre>
 * BaiduSearchTool    → Spring Bean 名 "baiduSearchTool"  ← @Component 默认
 * BaiduSearchService → Spring Bean 名 "baiduSearch"      ← 官方自动配置
 * 两者不冲突，各自独立
 * </pre>
 *
 * <p>官方库内部调用链路：</p>
 * <pre>
 * BaiduSearchTool.search(query, num)
 *   → BaiduSearchService.apply(new Request(query, num))
 *     → WebClient GET https://www.baidu.com/s?wd={query}
 *     → 设置随机 User-Agent / Referer / Accept-Language 头
 *     → JSoup 解析 HTML → 提取 #content_left > .c-container
 *     → 返回 Response{results: [SearchResult{title, abstractText, sourceUrl, icon}]}
 * </pre>
 *
 * <p>使用方式：</p>
 * <pre>
 * ChatClient.prompt("今天北京天气怎么样？")
 *     .tools(baiduSearchTool)
 *     .call()
 *     .content();
 * </pre>
 *
 * @see BaiduSearchService 官方百度 HTML 搜索实现
 * @see <a href="https://java2ai.com/docs/frameworks/agent-framework/tutorials/tools">Tools 文档</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaiduSearchTool {

    /**
     * 官方百度搜索服务（Bean 名: baiduSearch），由
     * {@link com.alibaba.cloud.ai.toolcalling.baidusearch.BaiduSearchAutoConfiguration} 自动创建。
     *
     * <p>该 Bean 无需 API Key，通过 HTML 抓取实现搜索，开箱即用。</p>
     */
    private final BaiduSearchService baiduSearchService;

    /**
     * 百度网页搜索。当 AI 模型判断需要实时信息时自动调用。
     *
     * <p>适用场景：</p>
     * <ul>
     *   <li>时事新闻、最新动态</li>
     *   <li>实时数据查询（天气、股价、赛事等）</li>
     *   <li>模型自身知识截止日期之后的信息</li>
     * </ul>
     *
     * @param query 搜索关键词（必填，不可为空）
     * @param num   返回结果数量（可选，默认 10 条，上限 50）
     * @return 格式化后的搜索结果文本，供 AI 模型二次加工或直接呈现
     */
    @Tool(description = """
            使用百度搜索引擎查询实时信息。\
            当用户询问时事新闻、最新动态、实时数据等\
            模型自身知识无法回答的问题时调用此工具。""")
    public String search(
            @ToolParam(description = "搜索关键词，例如'今天北京天气'、'最新AI新闻'") String query,
            @ToolParam(description = "返回结果数量，默认5条，最多10条") Integer num
    ) {
        // 1. 参数校验
        if (query == null || query.trim().isEmpty()) {
            return "搜索关键词不能为空";
        }

        int resultCount = (num != null && num > 0) ? Math.min(num, 10) : 5;
        log.info("[BAIDU_SEARCH] query={}, num={}", query, resultCount);

        try {
            // 2. 构造官方 Request，委托给官方 BaiduSearchService
            BaiduSearchService.Request request = new BaiduSearchService.Request(
                    query.trim(), resultCount);

            BaiduSearchService.Response response = baiduSearchService.apply(request);

            // 3. 结果校验
            if (response == null || response.results() == null || response.results().isEmpty()) {
                log.info("[BAIDU_SEARCH] 未找到相关搜索结果, query={}", query);
                return "未找到与「" + query + "」相关的搜索结果，请尝试更换关键词。";
            }

            // 4. 格式化结果
            StringBuilder sb = new StringBuilder();
            sb.append("以下是从百度搜索获取的关于「").append(query).append("」的结果：\n\n");

            int count = 0;
            for (BaiduSearchService.SearchResult result : response.results()) {
                count++;
                sb.append(count).append(". **").append(result.title()).append("**\n");
                if (result.abstractText() != null && !result.abstractText().isBlank()) {
                    sb.append("   ").append(result.abstractText()).append("\n");
                }
                if (result.sourceUrl() != null && !result.sourceUrl().isBlank()) {
                    sb.append("   🔗 ").append(result.sourceUrl()).append("\n");
                }
                sb.append("\n");
            }

            log.info("[BAIDU_SEARCH] 成功返回 {} 条结果", count);
            return sb.toString();

        } catch (Exception e) {
            log.warn("[BAIDU_SEARCH] 搜索异常: {}", e.getMessage(), e);
            return "百度搜索暂时不可用：" + e.getMessage();
        }
    }
}