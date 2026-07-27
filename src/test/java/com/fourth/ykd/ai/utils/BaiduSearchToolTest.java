package com.fourth.ykd.ai.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.toolcalling.baidusearch.BaiduSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 验证实时搜索的失败标识和默认结果数量契约。 */
class BaiduSearchToolTest {

    private final BaiduSearchService searchService = mock(BaiduSearchService.class);
    private final TimeTool timeTool = mock(TimeTool.class);
    private final BaiduSearchTool tool = new BaiduSearchTool(searchService, timeTool);

    @Test
    void shouldMarkBlankQueryAsRealtimeSearchFailure() {
        assertThat(tool.search(" ", null)).startsWith("实时搜索失败：");
    }

    @Test
    void shouldKeepFailureMarkerWhenSearchServiceThrows() {
        when(timeTool.getTimeInfo("now", null)).thenReturn("当前上海时间：2026-07-26 12:00:00");
        when(searchService.apply(any())).thenThrow(new IllegalStateException("network error"));

        assertThat(tool.search("今日全国新闻", 8)).startsWith("实时搜索失败：");
    }

    @Test
    void shouldUseEightResultsByDefault() {
        when(timeTool.getTimeInfo("now", null)).thenReturn("当前上海时间：2026-07-26 12:00:00");
        when(searchService.apply(any())).thenReturn(new BaiduSearchService.Response(List.of(
                new BaiduSearchService.SearchResult("标题", "摘要", "https://example.com", null))));

        tool.search("今日全国新闻", null);

        ArgumentCaptor<BaiduSearchService.Request> captor = ArgumentCaptor.forClass(BaiduSearchService.Request.class);
        verify(searchService).apply(captor.capture());
        assertThat(captor.getValue().limit()).isEqualTo(8);
        assertThat(captor.getValue().query()).isEqualTo("全国新闻 2026-07-26");
    }

    @Test
    void shouldTreatPartialNonEmptyResultsAsSuccess() {
        when(timeTool.getTimeInfo("now", null)).thenReturn("当前上海时间：2026-07-26 12:00:00");
        when(searchService.apply(any())).thenReturn(new BaiduSearchService.Response(List.of(
                new BaiduSearchService.SearchResult("唯一有效标题", "有效摘要", "https://example.com", null))));

        String result = tool.search("今日全国新闻", 8);

        assertThat(result).doesNotStartWith("实时搜索失败：");
        assertThat(result).contains("结果非空即表示查询成功", "唯一有效标题", "有效摘要");
    }}