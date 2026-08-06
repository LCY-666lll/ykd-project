package com.fourth.ykd.ai.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

/** 验证本地预路由只抢占高置信度意图。 */
class DeepSeekIntentRouterRuleTest {

    @Test
    void shouldBypassModelForExplicitIntent() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(builder.build()).thenReturn(chatClient);
        DeepSeekIntentRouter router = new DeepSeekIntentRouter(builder, chatMemory);

        UserIntent result = router.route("user-1", "请生成PDF", false);

        assertThat(result).isEqualTo(UserIntent.FILE_GENERATE);
        verifyNoInteractions(chatClient, chatMemory);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("routingCases")
    void shouldMatchOnlyExplicitIntent(
            String userText,
            boolean hasPendingImage,
            UserIntent expected
    ) {
        Optional<UserIntent> result =
                DeepSeekIntentRouter.matchExplicitIntent(userText, hasPendingImage);

        if (expected == null) {
            assertThat(result).isEmpty();
        } else {
            assertThat(result).contains(expected);
        }
    }

    private static Stream<Arguments> routingCases() {
        return Stream.of(
                // 明确文件请求。
                Arguments.of("请生成一份PDF", false, UserIntent.FILE_GENERATE),
                Arguments.of("把刚才的内容导出Word文档", false, UserIntent.FILE_GENERATE),
                Arguments.of("整理成Excel文件给我", false, UserIntent.FILE_GENERATE),
                Arguments.of("保存为DOCX", false, UserIntent.FILE_GENERATE),
                Arguments.of("把内容转为PDF", false, UserIntent.FILE_GENERATE),
                Arguments.of("做个Word文档", false, UserIntent.FILE_GENERATE),
                Arguments.of("导出成表格文件", false, UserIntent.FILE_GENERATE),
                Arguments.of("PDF怎么打开", false, null),
                Arguments.of("帮我生成WordPress站点", false, null),
                Arguments.of("帮我写一篇文章", false, null),
                Arguments.of("用表格总结今天的新闻", false, null),

                // 独立生图。
                Arguments.of("画一张小猫图片", false, UserIntent.IMAGE_GENERATE),
                Arguments.of("生成夏令营宣传海报", false, UserIntent.IMAGE_GENERATE),
                Arguments.of("设计一个头像", false, UserIntent.IMAGE_GENERATE),
                Arguments.of("帮我做个活动海报", false, UserIntent.IMAGE_GENERATE),
                Arguments.of("描述一只小猫", false, null),

                // 图片编辑依赖现有图片上下文。
                Arguments.of("把这张图的背景换成蓝色", true, UserIntent.IMAGE_EDIT),
                Arguments.of("去掉图片中的文字", true, UserIntent.IMAGE_EDIT),
                Arguments.of("给照片里的人物加上帽子", true, UserIntent.IMAGE_EDIT),
                Arguments.of("把这张图的背景换成蓝色", false, null),
                Arguments.of("修改一下这段文档", true, null),

                // 图片理解依赖现有图片上下文。
                Arguments.of("图片里写了什么", true, UserIntent.IMAGE_UNDERSTAND),
                Arguments.of("帮我识别一下", true, UserIntent.IMAGE_UNDERSTAND),
                Arguments.of("描述这张图片", true, UserIntent.IMAGE_UNDERSTAND),
                Arguments.of("这是谁？", true, UserIntent.IMAGE_UNDERSTAND),
                Arguments.of("图片里写了什么", false, null),

                // 只有明确要求输出语音时才匹配。
                Arguments.of("用语音告诉我今天的天气", false, UserIntent.VOICE_REPLY),
                Arguments.of("把结果读出来", false, UserIntent.VOICE_REPLY),
                Arguments.of("发一段语音回答我", false, UserIntent.VOICE_REPLY),
                Arguments.of("给我今天的新闻", false, null),
                Arguments.of("查询杭州天气", false, null),

                // 文件或图片交付优先于附带的语音要求。
                Arguments.of("用语音回复并生成PDF", false, UserIntent.FILE_GENERATE),
                Arguments.of("用语音说一下并生成一张图片", false, UserIntent.IMAGE_GENERATE),

                // 两种业务交付相互冲突时继续交给 DeepSeek。
                Arguments.of("把这张图片修改后放入PDF", true, null),
                Arguments.of("识别图片里的文字并把它删掉", true, null),
                Arguments.of("生成一张图片并导出PDF", false, null),

                // 空输入和省略指代必须保留模型上下文判断。
                Arguments.of(null, false, null),
                Arguments.of("   ", true, null),
                Arguments.of("按上面的再生成一份", false, null),
                Arguments.of("把刚才那个改一下", true, null)
        );
    }
}
