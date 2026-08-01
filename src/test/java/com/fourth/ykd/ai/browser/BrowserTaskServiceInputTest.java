package com.fourth.ykd.ai.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fourth.ykd.ai.mcp.BrowserMcpToolProvider;
import java.net.InetAddress;
import com.fourth.ykd.ai.utils.WeatherTool;
import org.springframework.ai.tool.ToolCallback;
import org.junit.jupiter.api.Test;

class BrowserTaskServiceInputTest {

    @Test
    void shouldAskForActionWhenUserSendsOnlyPublicUrl() throws Exception {
        BrowserMcpToolProvider toolProvider = mock(BrowserMcpToolProvider.class);
        BrowserTaskChatClientFactory chatClientFactory = mock(BrowserTaskChatClientFactory.class);
        BrowserTaskService service = new BrowserTaskService(
                publicUrlPolicy(), toolProvider, chatClientFactory, mock(WeatherTool.class));
        when(toolProvider.getSafeToolCallbacks()).thenReturn(new ToolCallback[0]);

        String answer = service.execute("user-1", "https://developer.aliyun.com/article/1392013");

        assertThat(answer).contains("暂未启用");
    }

    private static BrowserUrlPolicy publicUrlPolicy() throws Exception {
        return new BrowserUrlPolicy(host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34")
        });
    }
}
