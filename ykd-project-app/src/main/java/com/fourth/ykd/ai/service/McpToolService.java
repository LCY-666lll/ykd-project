package com.fourth.ykd.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具服务：封装外部 MCP Server 的工具调用。
 * 后端代码直接调用工具，不通过 AI 的 toolCallbacks 机制。
 */
@Slf4j
@Service
public class McpToolService {

    private final List<ToolCallbackProvider> mcpToolCallbackProviders;

    public McpToolService(List<ToolCallbackProvider> mcpToolCallbackProviders) {
        this.mcpToolCallbackProviders = mcpToolCallbackProviders;
    }

    /**
     * 获取所有可用工具信息（名称、描述、参数），用于构建系统提示。
     */
    //查找工具
    public List<ToolInfo> getAvailableTools() {
        List<ToolInfo> tools = new ArrayList<>();
        for (ToolCallbackProvider provider : mcpToolCallbackProviders) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                tools.add(new ToolInfo(
                        callback.getToolDefinition().name(),
                        callback.getToolDefinition().description(),
                        callback.getToolDefinition().inputSchema()
                ));
            }
        }
        return tools;
    }

    /**
     * 根据名称调用工具。
     *
     * @param toolName 工具名称
     * @param argsJson 参数 JSON 字符串
     * @return 工具执行结果
     */
    //根据查找到的工具名进行调用
    public String callTool(String toolName, String argsJson) {
        log.info("[MCP][TOOL_CALL] tool={}, args={}", toolName, argsJson);
        for (ToolCallbackProvider provider : mcpToolCallbackProviders) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                if (callback.getToolDefinition().name().equals(toolName)) {
                    try {
                        String result = callback.call(argsJson);
                        log.info("[MCP][TOOL_RESULT] tool={}, result={}", toolName,
                                result.length() > 500 ? result.substring(0, 500) + "..." : result);
                        return result;
                    } catch (Exception e) {
                        log.error("[MCP][TOOL_ERROR] tool={}, error={}", toolName, e.getMessage());
                        return "工具调用失败: " + e.getMessage();
                    }
                }
            }
        }
        log.warn("[MCP][TOOL_NOT_FOUND] tool={}", toolName);
        return "未找到工具: " + toolName;
    }

    /**
     * 构建猎聘工具描述文本，注入系统提示让 AI 知道如何调用猎聘工具。
     * 猎聘工具走 MCP，不走 Spring AI toolCallbacks，所以需要在系统提示中描述格式。
     */
    //告诉AI如何调用猎聘工具
    public String buildLiepinToolDescriptions() {
        List<ToolInfo> tools = getAvailableTools().stream()
                .filter(t -> t.name().startsWith("search_liepin") || t.name().startsWith("apply_liepin"))
                .toList();
        if (tools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## 猎聘工具（MCP 调用）\n");
        sb.append("当需要调用猎聘工具时，输出以下格式（不要用其他方式调用）：\n");
        sb.append("<<TOOL_CALL:工具名>>{\"参数名\":\"参数值\"}<<END>>\n\n");
        for (ToolInfo tool : tools) {
            sb.append("### ").append(tool.name()).append("\n");
            sb.append("描述: ").append(tool.description()).append("\n");
            sb.append("参数JSON Schema: ").append(tool.inputSchema()).append("\n\n");
        }
        return sb.toString();
    }

    public record ToolInfo(String name, String description, String inputSchema) {}
}