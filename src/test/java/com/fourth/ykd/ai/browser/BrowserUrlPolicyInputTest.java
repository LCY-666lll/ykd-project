package com.fourth.ykd.ai.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class BrowserUrlPolicyInputTest {

    @Test
    void shouldExtractUrlBeforeChineseTaskText() throws Exception {
        BrowserUrlPolicy policy = new BrowserUrlPolicy(host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34")
        });

        BrowserUrlPolicy.ValidationResult result = policy.validateUserUrl(
                "https://interview.javaguide.cn/帮我总结一下这个内容"
        );

        assertThat(result.allowed()).isTrue();
        assertThat(result.url()).isEqualTo("https://interview.javaguide.cn/");
    }
}
