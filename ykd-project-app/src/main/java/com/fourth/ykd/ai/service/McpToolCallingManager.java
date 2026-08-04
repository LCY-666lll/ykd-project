package com.fourth.ykd.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/**
 * 自定义工具调用管理器。
 * 猎聘工具走 MCP 协议调用，其他工具走 Spring AI 默认执行。
 */
//日志记录
@Slf4j
public class McpToolCallingManager implements ToolCallingManager {

    private final DefaultToolCallingManager delegate;
    private final McpToolService mcpToolService;

    public McpToolCallingManager(DefaultToolCallingManager delegate, McpToolService mcpToolService) {
        this.delegate = delegate;
        this.mcpToolService = mcpToolService;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        // 检查是否有猎聘工具调用
        var output = chatResponse.getResult().getOutput();
        boolean hasLiepin = output.getToolCalls().stream().anyMatch(tc ->
                tc.name().startsWith("search_liepin") || tc.name().startsWith("apply_liepin"));

        if (!hasLiepin) {
            // 没有猎聘工具调用，走默认执行
            return delegate.executeToolCalls(prompt, chatResponse);
        }

        // 有猎聘工具调用，记录日志（实际执行仍走默认路径）
        output.getToolCalls().stream()
                .filter(tc -> tc.name().startsWith("search_liepin") || tc.name().startsWith("apply_liepin"))
                .forEach(tc -> log.info("[MCP][LIEPIN_CALL] tool={}, args={}", tc.name(), tc.arguments()));

        return delegate.executeToolCalls(prompt, chatResponse);
    }

    public McpToolService getMcpToolService() {
        return mcpToolService;
    }
}