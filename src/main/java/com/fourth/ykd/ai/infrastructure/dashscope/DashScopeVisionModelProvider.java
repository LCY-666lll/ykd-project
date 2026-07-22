package com.fourth.ykd.ai.infrastructure.dashscope;

import org.springframework.ai.openai.OpenAiChatModel;

// 将识图模型包装为专用 Bean，防止 Spring 将它当作默认文本模型。
// 千问图片识别模型的专用包装，用于按名称注入而不参与默认模型选择。
public record DashScopeVisionModelProvider(OpenAiChatModel model) {
}