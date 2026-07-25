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

    // 声明百度搜索服务接口。
    // 由Spring AI Alibaba 提供的官方服务，封装了百度搜索API的调用
    private final BaiduSearchService baiduSearchService;

    // 日期提取正则表达式模式，用于从时间字符串中提取yyyy-MM-dd格式的日期
    // 正则含义：20开头的年份(20\\d{2}) + 连字符 + 两位月份(\\d{2}) + 连字符 + 两位日期(\\d{2})
    private static final Pattern CURRENT_DATE_PATTERN = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");

    // 声明时间工具。调用项目内部的工具类 TimeTool，用于获取当前上海时间
    private final TimeTool timeTool;


    /**
     * 百度搜索工具方法
     *
     * 调用流程：
     *    校验搜索关键词是否为空
     *  ->调用TimeTool获取当前上海时间
     *  ->从时间字符串中提取 yyyy-MM-dd 格式日期
     *  ->构建搜索词：用户关键词 + 当前日期
     *  ->调用百度搜索服务
     *  ->将搜索结果整理为可读文本
     *
     * @param query 搜索关键词
     * @param num   返回结果数量，默认8条，范围5~10条
     * @return      格式化的搜索结果文本，或固定错误提示信息
     */
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

        // 规范化搜索关键词，去除首尾空格
        String normalizedQuery = query.trim();
        // 调用 TimeTool 获取当前上海时间
        String currentTime = timeTool.getTimeInfo("now", null);
        // 调用方法 extractCurrentDate() ，传入完整时间字符串，返回格式为 yyyy-MM-dd 的日期
        String currentDate = extractCurrentDate(currentTime);
        // 日期提取失败处理：记录警告日志，返回固定错误提示
        if (currentDate == null) {
            log.warn("[AI][TOOL][BAIDU_SEARCH][FAILED] query={}, reason=未获取当前上海日期", normalizedQuery);
            return "实时搜索失败：未获取当前上海日期，请稍后重试。";
        }
        // 构建最终搜索词：用户输入的关键词 + 当前日期
        String searchQuery = normalizedQuery + " " + currentDate;
        // 返回结果数量：默认8条，范围 5~8条
        int resultCount = num == null ? 8 : Math.max(5, Math.min(num, 10));

        // 搜索开始日志：搜索关键词、当前时间、返会结果数量
        log.info("[AI][TOOL][BAIDU_SEARCH][START] query={}, currentTime={}, num={}",
                normalizedQuery, currentTime, resultCount);
        try {
            // 调用百度搜索服务执行搜索
            BaiduSearchService.Response response = baiduSearchService.apply(
                    new BaiduSearchService.Request(searchQuery, resultCount)
            );
            // 搜索结果校验。  响应为空 || 结果列表为空 || 结果列表为空集合
            // 记录警告日志，返回固定错误提示
            if (response == null || response.results() == null || response.results().isEmpty()) {
                log.warn("[AI][TOOL][BAIDU_SEARCH][FAILED] query={}, reason=未获取有效搜索结果", searchQuery);
                return "实时搜索失败：未获取有效搜索结果，请稍后重试。";
            }

            // 构建结果文本：使用 StringBuilder 而非 String 直接拼接
            // 原因：String 不可变，每次 "+" 都会创建新对象；StringBuilder 在同一对象上追加，避免大量临时对象产生
            // 本场景需循环拼接 5~10 条搜索结果，使用 StringBuilder 可显著提升性能
            StringBuilder resultText = new StringBuilder();
            // 拼接结果头部信息：使用链式调用（append 返回 this）提高代码可读性
            resultText.append("以下是从百度搜索获取的关于“")
                    .append(normalizedQuery)             //用户输入的搜索关键词
                    .append("”的结果，检索时间：")
                    .append(currentTime)                 // 当前上海时间（含日期和星期）
                    .append("：\n\n");
            // 结果计数器：用于为每条搜索结果生成序号（1、2、3...）
            int count = 0;
            // 遍历搜索结果列表
            for (BaiduSearchService.SearchResult result : response.results()) {
                count++;
                // 添加序号和标题，标题使用Markdown加粗
                resultText.append(count).append(". **").append(result.title()).append("**\n");
                // 如果存在摘要（abstractText），缩进3个空格后添加
                if (result.abstractText() != null && !result.abstractText().isBlank()) {
                    resultText.append("   ").append(result.abstractText()).append("\n");
                }
                // 如果存在来源链接（sourceUrl），缩进3个空格后添加
                if (result.sourceUrl() != null && !result.sourceUrl().isBlank()) {
                    resultText.append("   ").append(result.sourceUrl()).append("\n");
                }
                // 每个结果之间添加空行分隔
                resultText.append("\n");
            }

            // 记录搜索成功日志，包含 完整的搜索词 和 实际返回数量
            log.info("[AI][TOOL][BAIDU_SEARCH][SUCCESS] query={}, resultCount={}", searchQuery, count);
            // 返回搜索结果文本
            return resultText.toString();
        } catch (Exception exception) {
            // 捕获所有异常，包括网络异常、API错误等
            log.warn("[AI][TOOL][BAIDU_SEARCH][FAILED] query={}, reason={}",
                    searchQuery, exception.getMessage());
            // 返回固定错误提示
            return "百度搜索暂时不可用，请稍后重试。";
        }
    }

    /**
     * 从时间字符串中提取日期部分
     */
    private String extractCurrentDate(String currentTime) {
        // 创建正则匹配器，调用Pattern.matcher(CharSequence input)，创建一个 Matcher 对象
        // 空值保护避免：当currentTime=null时，传入空字符串
        Matcher matcher = CURRENT_DATE_PATTERN.matcher(currentTime == null ? "" : currentTime);
        // 查找匹配日期：找到返回 第一个捕获组（日期部分：年月日），没找到返回null
        return matcher.find() ? matcher.group(1) : null;
    }
}