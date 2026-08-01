package com.fourth.ykd.ai.mcp;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(
        named = "RUN_BROWSER_MCP_INTEGRATION_TEST",
        matches = "true"
)
class PlaywrightMcpStartupIntegrationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(AutoConfigurations.of(
                            StdioTransportAutoConfiguration.class,
                            McpClientAutoConfiguration.class,
                            McpToolCallbackAutoConfiguration.class
                    ))
                    .withPropertyValues(
                            "spring.ai.mcp.client.enabled=true"
                    );

    @Test
    void shouldStartPlaywrightMcpAndDiscoverTools() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            McpStdioClientProperties properties =
                    context.getBean(McpStdioClientProperties.class);

            assertThat(properties.getConnections()).containsKey("playwright");

            McpStdioClientProperties.Parameters playwright =
                    properties.getConnections().get("playwright");

            assertThat(playwright.command()).isEqualTo("cmd.exe");
            assertThat(playwright.args()).containsExactly(
                    "/c",
                    "npx",
                    "-y",
                    "@playwright/mcp@0.0.78",
                    "--headless",
                    "--isolated",
                    "--browser",
                    "msedge",
                    "--timeout-action",
                    "5000",
                    "--timeout-navigation",
                    "30000",
                    "--snapshot-mode",
                    "full"
            );

            SyncMcpToolCallbackProvider provider =
                    context.getBean(SyncMcpToolCallbackProvider.class);

            Set<String> toolNames = Arrays.stream(provider.getToolCallbacks())
                    .map(callback -> callback.getToolDefinition().name())
                    .collect(Collectors.toSet());

            assertThat(toolNames).contains(
                    "browser_navigate",
                    "browser_snapshot",
                    "browser_click",
                    "browser_close"
            );
        });
    }
}
