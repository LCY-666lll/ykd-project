package com.fourth.ykd.ai.config;

import com.fourth.ykd.ai.utils.*;
import com.fourth.ykd.ilink.tool.LiepinApplyTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具配置。
 * 所有工具（含猎聘）通过嵌入式 MCP Server 暴露，后端通过 MCP 协议调用。
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(
            BaiduSearchTool baiduSearchTool,
            WeatherTool weatherTool,
            MathCalculatorTool mathCalculatorTool,
            TranslationTool translationTool,
            TimeTool timeTool,
            ScheduledTaskTool scheduledTaskTool,
            EmailTool emailTool,
            QrCodeTool qrCodeTool,
            LiepinApplyTool liepinApplyTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(
                        baiduSearchTool,
                        weatherTool,
                        mathCalculatorTool,
                        translationTool,
                        timeTool,
                        scheduledTaskTool,
                        emailTool,
                        qrCodeTool,
                        liepinApplyTool)
                .build();
    }
}