package com.fourth.ykd.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fourth.ykd.ai.dto.PendingUserImage;
import org.junit.jupiter.api.Test;

class InMemoryImageContextServiceTest {

    @Test
    void shouldSaveFindAndRemoveCurrentUserImage() {
        InMemoryImageContextService service = new InMemoryImageContextService();
        byte[] imageBytes = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        service.save("wechat-user", imageBytes);
        PendingUserImage image = service.findActive("wechat-user").orElseThrow();

        assertThat(image.bytes()).isSameAs(imageBytes);
        assertThat(image.contentType()).isEqualTo("image/png");

        service.remove("wechat-user", image);
        assertThat(service.findActive("wechat-user")).isEmpty();
    }
}