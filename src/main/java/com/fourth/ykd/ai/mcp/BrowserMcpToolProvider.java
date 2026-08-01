package com.fourth.ykd.ai.mcp;

import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/*从 Spring AI 提供的全部 MCP 工具中，筛选出允许给“浏览器任务”使用的工具。
 *MCP 工具过滤器
 拿到全部工具
    ↓
 按工具名过滤
    ↓
 返回浏览器任务允许使用的工具*/
@Component
public class BrowserMcpToolProvider {

    private static final Set<String> ALLOWED_TOOL_NAMES = Set.of(
            "browser_navigate",
            "browser_snapshot",
            "browser_find",
            "browser_click",
            "browser_type",
            "browser_fill_form",
            "browser_select_option",
            "browser_wait_for",
            "browser_tabs",
            "browser_navigate_back",
            "browser_take_screenshot",
            "browser_close"
    );

    //SyncMcpToolCallbackProvider 的作用可以理解成：提供 MCP 工具回调对象的组件
    private final Optional<SyncMcpToolCallbackProvider> mcpToolCallbackProvider;

    public BrowserMcpToolProvider(Optional<SyncMcpToolCallbackProvider> mcpToolCallbackProvider) {
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
    }

    /**
     * 返回允许提供给浏览器任务的工具。
     * MCP 关闭时返回空数组，未知工具默认不放行。
     */
    public ToolCallback[] getSafeToolCallbacks(){
        if (mcpToolCallbackProvider.isEmpty()){
            return new ToolCallback[0];
        }
        return Arrays.stream(mcpToolCallbackProvider.get().getToolCallbacks())
                .filter(this::isAllowed)
                //把保留下来的工具重新转换成 ToolCallback[]
                .toArray(ToolCallback[]::new);
    }

    /**
     * 判断指定 MCP 工具是否允许提供给浏览器任务。
     * 放行条件：
     * 1. ToolCallback 对象不为空；
     * 2. 工具定义不为空；
     * 3. 工具名称存在于浏览器工具白名单中。
     * @param callback 当前待检查的 MCP 工具回调
     * @return 满足全部白名单条件时返回 true，否则返回 false
     */
    private boolean isAllowed(ToolCallback callback) {
        return callback != null
                //callback 是工具对象
                && callback.getToolDefinition() != null
                && ALLOWED_TOOL_NAMES.contains(callback.getToolDefinition().name()
        );
    }
}