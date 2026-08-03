package com.fourth.ykd.ai.utils;

import com.alibaba.cloud.ai.toolcalling.baidusearch.BaiduSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 百度实时搜索工具。
 * 模型自动调用本工具后，工具固定先获取当前上海时间，再执行百度搜索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaiduSearchTool {

    private final BaiduSearchService baiduSearchService;

    private static final Pattern CURRENT_DATE_PATTERN = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");
    private static final Pattern RELATIVE_DAY_PATTERN = Pattern.compile("今天|今日|当天");

    private final TimeTool timeTool;

    @Tool(name = "search_realtime_information", description = """
            查询新闻、时事、政策、经济、科技等实时信息。
            用户询问新闻、最新动态、今天发生了什么、某地区新闻时调用。
            用户未明确地区时，搜索关键词应优先使用“中国全国新闻”；用户明确地区时，使用该地区名称搜索。
            用户追问新闻详情、来源、原文或链接时再次调用，并返回对应搜索结果。
            本工具会自动先获取上海当前时间，再执行百度搜索。
            搜索服务实际返回多少条有效结果，就基于这些结果回答多少条；结果少于请求数量不代表搜索失败。
            天气、数学计算和翻译请求不得调用本工具。
            """)
    public String search(
            @ToolParam(description = "搜索关键词，例如“今日全国新闻”或“人工智能最新动态”", required = true)
            String query,
            @ToolParam(description = "返回结果数量，默认8条，范围5到10条；首次新闻查询传8", required = false)
            Integer num
    ) {
        if (query == null || query.trim().isEmpty()) {
            return "实时搜索失败：搜索关键词不能为空。";
        }

        String normalizedQuery = normalizeQuery(query.trim());
        String currentTime = timeTool.getTimeInfo("now", null);
        String currentDate = extractCurrentDate(currentTime);
        if (currentDate == null) {
            log.warn("[AI][TOOL][BAIDU_SEARCH][FAILED] query={}, reason=未获取当前上海日期", normalizedQuery);
            return "实时搜索失败：未获取当前上海日期，请稍后重试。";
        }
        String searchQuery = normalizedQuery + " " + currentDate;
        int resultCount = num == null ? 8 : Math.max(5, Math.min(num, 10));

        log.info("[AI][TOOL][BAIDU_SEARCH][START] query={}, currentTime={}, num={}",
                normalizedQuery, currentTime, resultCount);
        try {
            BaiduSearchService.Request request = new BaiduSearchService.Request(searchQuery, resultCount);
            BaiduSearchService.Response response = baiduSearchService.apply(request);
            if (!hasResults(response)) {
                log.warn("[AI][TOOL][BAIDU_SEARCH][RETRY] query={}, reason=首次未获取有效搜索结果", searchQuery);
                response = baiduSearchService.apply(request);
            }
            if (!hasResults(response)) {
                log.warn("[AI][TOOL][BAIDU_SEARCH][FAILED] query={}, reason=重试后仍无有效搜索结果", searchQuery);
                return "实时搜索失败：未获取有效搜索结果，请稍后重试。";
            }

            StringBuilder resultText = new StringBuilder();
            resultText.append("以下是从百度搜索获取的关于“")
                    .append(normalizedQuery)
                    .append("”的结果，检索时间：")
                    .append(currentTime)
                    .append("。搜索结果非空即表示查询成功，即使少于请求数量也应据实回答：\n\n");
            int count = 0;
            for (BaiduSearchService.SearchResult result : response.results()) {
                count++;
                resultText.append(count).append(". **").append(result.title()).append("**\n");
                if (result.abstractText() != null && !result.abstractText().isBlank()) {
                    resultText.append("   ").append(result.abstractText()).append("\n");
                }
                if (result.sourceUrl() != null && !result.sourceUrl().isBlank()) {
                    resultText.append("   ").append(result.sourceUrl()).append("\n");
                }
                resultText.append("\n");
            }

            log.info("[AI][TOOL][BAIDU_SEARCH][SUCCESS] query={}, resultCount={}", searchQuery, count);
            return resultText.toString();
        } catch (Exception exception) {
            log.warn("[AI][TOOL][BAIDU_SEARCH][FAILED] query={}, reason={}",
                    searchQuery, exception.getMessage());
            return "实时搜索失败：百度搜索暂时不可用，请稍后重试。";
        }
    }
    private String normalizeQuery(String query) {
        String normalized = RELATIVE_DAY_PATTERN.matcher(query).replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.isEmpty() ? query : normalized;
    }

    private boolean hasResults(BaiduSearchService.Response response) {
        return response != null && response.results() != null && !response.results().isEmpty();
    }

    private String extractCurrentDate(String currentTime) {
        Matcher matcher = CURRENT_DATE_PATTERN.matcher(currentTime == null ? "" : currentTime);
        return matcher.find() ? matcher.group(1) : null;
    }
}