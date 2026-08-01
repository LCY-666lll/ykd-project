package com.fourth.ykd.ai.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;

/** 验证意图路由结果完全由模型结合上下文决定。 */
class DeepSeekIntentRouterTest {

    @Test
    void shouldTreatGeneratedImageSourceQuestionAsText() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatMemory.get("user-1")).thenReturn(List.of());
        when(chatClient.prompt()
                .system(anyString())
                .user("你生成图片的参考依据是什么，天气的来源")
                .call()
                .content())
                .thenReturn("{\"intent\":\"TEXT\"}");

        DeepSeekIntentRouter router = new DeepSeekIntentRouter(builder, chatMemory);

        UserIntent result = router.route(
                "user-1",
                "你生成图片的参考依据是什么，天气的来源",
                false
        );

        assertThat(result).isEqualTo(UserIntent.TEXT);
    }

    @Test
    void shouldUseMemoryManageWhenModelChoosesIt() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatMemory.get("user-1")).thenReturn(List.of());
        when(chatClient.prompt()
                .system(anyString())
                .user("以后没有指定城市时默认查询杭州天气")
                .call()
                .content())
                .thenReturn("{\"intent\":\"MEMORY_MANAGE\"}");

        DeepSeekIntentRouter router = new DeepSeekIntentRouter(builder, chatMemory);

        UserIntent result = router.route(
                "user-1",
                "以后没有指定城市时默认查询杭州天气",
                false
        );

        assertThat(result).isEqualTo(UserIntent.MEMORY_MANAGE);
    }
    @Test
    void shouldUseImageGenerationWhenModelChoosesIt() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatMemory.get("user-1")).thenReturn(List.of());
        when(chatClient.prompt()
                .system(anyString())
                .user("生成一张杭州旅行海报")
                .call()
                .content())
                .thenReturn("{\"intent\":\"IMAGE_GENERATE\"}");

        DeepSeekIntentRouter router = new DeepSeekIntentRouter(builder, chatMemory);

        UserIntent result = router.route(
                "user-1",
                "生成一张杭州旅行海报",
                false
        );

        assertThat(result).isEqualTo(UserIntent.IMAGE_GENERATE);
    }

    @Test
    void shouldUseBrowserTaskWhenModelChoosesItForExplicitUrl() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatMemory chatMemory = mock(ChatMemory.class);
        String userText = "打开 https://example.com 并查看页面标题";
        when(builder.build()).thenReturn(chatClient);
        when(chatMemory.get("user-1")).thenReturn(List.of());
        when(chatClient.prompt().system(anyString()).user(userText).call().content())
                .thenReturn("{\"intent\":\"BROWSER_TASK\"}");

        DeepSeekIntentRouter router = new DeepSeekIntentRouter(builder, chatMemory);

        assertThat(router.route("user-1", userText, false)).isEqualTo(UserIntent.BROWSER_TASK);
    }

    @Test
    void shouldDowngradeBrowserTaskWithoutExplicitUrlToText() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatMemory chatMemory = mock(ChatMemory.class);
        String userText = "帮我打开学校官网查看通知";
        when(builder.build()).thenReturn(chatClient);
        when(chatMemory.get("user-1")).thenReturn(List.of());
        when(chatClient.prompt().system(anyString()).user(userText).call().content())
                .thenReturn("{\"intent\":\"BROWSER_TASK\"}");

        DeepSeekIntentRouter router = new DeepSeekIntentRouter(builder, chatMemory);

        assertThat(router.route("user-1", userText, false)).isEqualTo(UserIntent.TEXT);
    }

    @Test
    void shouldRouteBareUrlToBrowserTaskWhenModelChoosesText() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatMemory chatMemory = mock(ChatMemory.class);
        String userText = "https://developer.aliyun.com/article/1392013";
        when(builder.build()).thenReturn(chatClient);
        when(chatMemory.get("user-1")).thenReturn(List.of());
        when(chatClient.prompt().system(anyString()).user(userText).call().content())
                .thenReturn("{\"intent\":\"TEXT\"}");

        DeepSeekIntentRouter router = new DeepSeekIntentRouter(builder, chatMemory);

        assertThat(router.route("user-1", userText, false)).isEqualTo(UserIntent.BROWSER_TASK);
    }
}