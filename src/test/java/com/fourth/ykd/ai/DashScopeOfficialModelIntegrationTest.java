package com.fourth.ykd.ai;

import com.fourth.ykd.ai.dto.GeneratedAudio;
import com.fourth.ykd.ai.dto.GeneratedImage;
import com.fourth.ykd.ai.dto.PendingUserImage;
import com.fourth.ykd.ai.service.AudioSynthesisService;
import com.fourth.ykd.ai.service.ImageGenerationService;
import com.fourth.ykd.ai.service.ImageReferenceGenerationService;
import com.fourth.ykd.ai.service.ImageUnderstandingService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StringUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"ilink.enabled=false", "spring.main.web-application-type=none"})
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_INTEGRATION_TEST", matches = "true")
// 千问官方模型真实集成测试，覆盖文生图、图生图、识图和语音合成。
class DashScopeOfficialModelIntegrationTest {

    // 使用大于模型最小尺寸要求的 PNG，避免测试图片自身导致请求失败。
    private static final byte[] TEST_IMAGE = createTestImage();

    private static byte[] createTestImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x00FF00);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
        catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
    @Autowired
    private ImageGenerationService imageGenerationService;

    @Autowired
    private ImageUnderstandingService imageUnderstandingService;

    @Autowired
    private ImageReferenceGenerationService imageReferenceGenerationService;

    @Autowired
    private AudioSynthesisService audioSynthesisService;

    // 验证官方 SDK 的文生图调用。
    @Test
    void shouldGenerateImage() {
        GeneratedImage image = imageGenerationService.generate("\u4e00\u53ea\u7b80\u7b14\u753b\u7684\u6a59\u8272\u732b");
        assertTrue(image.bytes().length > 0);
    }


    // 验证官方 SDK 的参考图生成调用。
    @Test
    void shouldGenerateImageFromReference() {
        GeneratedImage image = imageReferenceGenerationService.generate(
                new PendingUserImage(TEST_IMAGE, "image/png", Instant.now()), "\u5c06\u56fe\u7247\u6539\u4e3a\u84dd\u8272\u8c03");
        assertTrue(image.bytes().length > 0);
    }
    // 验证 DashScope 兼容端点能识别本地图片字节。
    @Test
    void shouldUnderstandImage() {
        String answer = imageUnderstandingService.understand(
                new PendingUserImage(TEST_IMAGE, "image/png", Instant.now()), "\u8fd9\u662f\u4ec0\u4e48\u56fe\u7247\uff1f");
        assertTrue(StringUtils.hasText(answer));
    }

    // 验证流式 CosyVoice 最终返回完整 MP3 文件。
    @Test
    void shouldSynthesizePlayableAudio() {
        GeneratedAudio audio = audioSynthesisService.synthesize("\u4f60\u597d\uff0c\u8fd9\u662f\u4e00\u6bb5\u8bed\u97f3\u6d4b\u8bd5\u3002");
        assertTrue(audio.bytes().length >= 2 * 1024);
        assertTrue(audio.bytes()[0] == 'I' && audio.bytes()[1] == 'D' && audio.bytes()[2] == '3'
                || audio.bytes()[0] == (byte) 0xFF && audio.bytes()[1] == (byte) 0xFB);
    }
}