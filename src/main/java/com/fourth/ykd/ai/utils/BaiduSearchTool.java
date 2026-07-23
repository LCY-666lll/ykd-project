package com.fourth.ykd.ai.utils;

import com.alibaba.cloud.ai.toolcalling.baidusearch.BaiduSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/*
 * @see BaiduSearchService 官方百度 HTML 搜索实现
 * @see <a href="https://java2ai.com/docs/frameworks/agent-framework/tutorials/tools">Tools 文档</a>
 *DeepSeek 判断需要实时信息
        ↓
调用 BaiduSearchTool.search(...)
        ↓
校验 query 和 num
        ↓
构造 BaiduSearchService.Request
        ↓
调用官方 BaiduSearchService
        ↓
检查是否有搜索结果
        ↓
格式化标题、摘要、链接
        ↓
返回字符串给模型
        ↓
模型组织最终回答
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaiduSearchTool {

    /**
     * 官方百度搜索服务，由 Spring AI Alibaba 自动创建。
     */
    private final BaiduSearchService baiduSearchService;

    @Tool(description = """
            使用百度搜索引擎查询实时信息。
            当用户询问时事新闻、最新动态、实时数据等模型自身知识无法回答的问题时调用。
            当用户问题中出现最新、今日、昨日、最近等关键词时，优先搜索真实世界信息。
            """)
    public String search(
            @ToolParam(
                    description = "搜索关键词，例如“今天北京天气”或“最新AI新闻”",
                    required = true
            )
            String query,

            @ToolParam(
                    description = "返回结果数量，默认5条，最多10条",
                    required = false
            )
            Integer num
    ) {
        if (query == null || query.trim().isEmpty()) {
            return "搜索关键词不能为空";
        }

        String normalizedQuery = query.trim();
        int resultCount = num != null && num > 0
                ? Math.min(num, 10)
                : 5;

        log.info(
                "[AI][TOOL][BAIDU_SEARCH][START][百度搜索工具开始执行] query={}, num={}",
                normalizedQuery,
                resultCount
        );

        try {
            BaiduSearchService.Request request =
                    new BaiduSearchService.Request(
                            normalizedQuery,
                            resultCount
                    );

            BaiduSearchService.Response response =
                    baiduSearchService.apply(request);

            if (response == null
                    || response.results() == null
                    || response.results().isEmpty()) {

                log.info(
                        "[AI][TOOL][BAIDU_SEARCH][OVER][未找到搜索结果] query={}",
                        normalizedQuery
                );

                return "未找到与「" + normalizedQuery
                        + "」相关的搜索结果，请尝试更换关键词。";
            }

            StringBuilder resultText = new StringBuilder();
            resultText.append("以下是从百度搜索获取的关于「")
                    .append(normalizedQuery)
                    .append("」的结果：\n\n");

            int count = 0;

            for (BaiduSearchService.SearchResult result
                    : response.results()) {

                count++;

                resultText.append(count)
                        .append(". **")
                        .append(result.title())
                        .append("**\n");

                if (result.abstractText() != null
                        && !result.abstractText().isBlank()) {
                    resultText.append("   ")
                            .append(result.abstractText())
                            .append("\n");
                }

                if (result.sourceUrl() != null
                        && !result.sourceUrl().isBlank()) {
                    resultText.append("   🔗 ")
                            .append(result.sourceUrl())
                            .append("\n");
                }

                resultText.append("\n");
            }

            log.info(
                    "[AI][TOOL][BAIDU_SEARCH][OVER][百度搜索工具调用完毕] query={}, resultCount={}",
                    normalizedQuery,
                    count
            );

            return resultText.toString();

        } catch (Exception exception) {
            log.warn(
                    "[AI][TOOL][BAIDU_SEARCH][FAILED][百度搜索工具调用失败] query={}, reason={}",
                    normalizedQuery,
                    exception.getMessage()
            );

            return "百度搜索暂时不可用，请稍后重试。";
        }
    }
}