package com.fourth.ykd.ai.mcp;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrowserMcpToolProviderTest {

    private static final List<String> SAFE_TOOL_NAMES = List.of(
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

    @Test
    void shouldReturnNoToolsWhenMcpProviderIsUnavailable() {
        BrowserMcpToolProvider provider =
                new BrowserMcpToolProvider(Optional.empty());

        assertThat(provider.getSafeToolCallbacks()).isEmpty();
    }

    @Test
    void shouldKeepEveryConfiguredSafeTool() {
        ToolCallback[] callbacks = SAFE_TOOL_NAMES.stream()
                .map(this::callback)
                .toArray(ToolCallback[]::new);

        BrowserMcpToolProvider provider = providerWith(callbacks);

        assertThat(toolNames(provider.getSafeToolCallbacks()))
                .containsExactlyElementsOf(SAFE_TOOL_NAMES);
    }

    @Test
    void shouldFilterDangerousAndUnknownTools() {
        BrowserMcpToolProvider provider = providerWith(
                callback("browser_navigate"),
                callback("browser_evaluate"),
                callback("browser_run_code_unsafe"),
                callback("browser_file_upload"),
                callback("browser_future_unknown_tool")
        );

        assertThat(toolNames(provider.getSafeToolCallbacks()))
                .containsExactly("browser_navigate");
    }

    @Test
    void shouldNotRegisterAsGlobalToolCallbackProvider() {
        assertThat(ToolCallbackProvider.class.isAssignableFrom(
                BrowserMcpToolProvider.class
        )).isFalse();
    }

    private BrowserMcpToolProvider providerWith(ToolCallback... callbacks) {
        SyncMcpToolCallbackProvider delegate =
                mock(SyncMcpToolCallbackProvider.class);

        when(delegate.getToolCallbacks()).thenReturn(callbacks);

        return new BrowserMcpToolProvider(Optional.of(delegate));
    }

    private ToolCallback callback(String name) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);

        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);

        return callback;
    }

    private List<String> toolNames(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }
}
