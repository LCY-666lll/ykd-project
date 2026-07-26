package com.fourth.ykd.ai.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证用户明确指定的文件格式不会被模型返回值覆盖。 */
class FileGenerationToolTypeTest {

    private final FileGenerationTool tool = new FileGenerationTool(
            null, null, null, null, null, null, "");

    @Test
    void shouldPreferExplicitPdfOverModelDocx() {
        assertThat(tool.resolveTypes("请生成PDF文件", List.of("DOCX")))
                .containsExactly("PDF");
    }

    @Test
    void shouldSupportMultipleExplicitFormats() {
        assertThat(tool.resolveTypes("请同时生成Word、Excel和PDF", List.of("DOCX")))
                .containsExactly("DOCX", "XLSX", "PDF");
    }

    @Test
    void shouldTreatGeneratedTableAsXlsx() {
        assertThat(tool.resolveTypes("请生成表格，列出每日课程和负责人", List.of("DOCX")))
                .containsExactly("XLSX");
    }

    @Test
    void shouldNotTreatPdfTableLayoutAsExtraXlsxFile() {
        assertThat(tool.resolveTypes("请生成PDF，内容用表格展示", List.of("DOCX")))
                .containsExactly("PDF");
    }

    @Test
    void shouldUseModelTypeAndKeepDocxFallbackWhenUserDidNotSpecifyFormat() {
        assertThat(tool.resolveTypes("整理报名须知", List.of("PDF")))
                .containsExactly("PDF");
        assertThat(tool.resolveTypes("整理报名须知", List.of("UNKNOWN")))
                .containsExactly("DOCX");
    }
}