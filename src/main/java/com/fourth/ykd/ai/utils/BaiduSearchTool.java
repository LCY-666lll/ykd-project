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

    private final TimeTool timeTool;

    @Tool(description = """
            查询新闻、时事、政策、经济、科技等实时信息。
            用户询问新闻、最新动态、今天发生了什么、某地区新闻时调用。
            用户未明确地区时，搜索关键词应优先使用“中国全国新闻”；用户明确地区时，使用该地区名称搜索。
            用户追问新闻详情、来源、原文或链接时再次调用，并返回对应搜索结果。
            本工具会自动先获取上海当前时间，再执行百度搜索。
            """)
    public String search(
            @ToolParam(description = "搜索关键词，例如“今天北京天气”或“最新AI新闻”", required = true)
            String query,
            @ToolParam(description = "返回结果数量，默认5条，最大10条", required = false)
            Integer num
    ) {
        if (query == null || query.trim().isEmpty()) {
            return "搜索关键词不能为空";
        }

        String normalizedQuery = query.trim();
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
            BaiduSearchService.Response response = baiduSearchService.apply(
                    new BaiduSearchService.Request(searchQuery, resultCount)
            );
            if (response == null || response.results() == null || response.results().isEmpty()) {
                log.warn("[AI][TOOL][BAIDU_SEARCH][FAILED] query={}, reason=未获取有效搜索结果", searchQuery);
                return "实时搜索失败：未获取有效搜索结果，请稍后重试。";
            }

            StringBuilder resultText = new StringBuilder();
            resultText.append("以下是从百度搜索获取的关于“")
                    .append(normalizedQuery)
                    .append("”的结果，检索时间：")
                    .append(currentTime)
                    .append("：\n\n");
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
            return "百度搜索暂时不可用，请稍后重试。";
        }
    }
    private String extractCurrentDate(String currentTime) {
        Matcher matcher = CURRENT_DATE_PATTERN.matcher(currentTime == null ? "" : currentTime);
        return matcher.find() ? matcher.group(1) : null;
    }
}