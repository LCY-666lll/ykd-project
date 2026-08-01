package com.fourth.ykd.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiChatServiceImplCapabilityInstructionsTest {

    @Test
    void shouldRequireAllCapabilitiesForCapabilityQuestions() {
        String instructions = AiChatServiceImpl.buildChatSystemInstructions();

        assertThat(instructions)
                .contains("全部八类能力", "不得为了简短而省略任何一类")
                .contains("PDF", "Word/DOCX", "Excel/XLSX")
                .contains("文生图", "参考图编辑", "图片识别", "语音回复")
                .contains("记住、查询、修改或删除长期偏好")
                .contains("公开 http 或 https 网址", "真实浏览器访问公开页面")
                .contains("不得登录")
                .contains("本轮 ChatClient 未直接挂载", "外层意图分流")
                .contains("旧回答均为过期错误信息");
    }
}
