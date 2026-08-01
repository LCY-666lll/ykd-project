package com.fourth.ykd.ai.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class BrowserUrlPolicyTest {

    @Test
    void shouldAllowExplicitPublicHttpsUrl() throws Exception {
        BrowserUrlPolicy policy = new BrowserUrlPolicy(host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34")
        });

        BrowserUrlPolicy.ValidationResult result = policy.validateUserUrl(
                "打开 https://example.com/news 并查看最新公告"
        );

        assertThat(result.allowed()).isTrue();
        assertThat(result.url()).isEqualTo("https://example.com/news");
    }

    @Test
    void shouldRejectMissingUrl() {
        BrowserUrlPolicy policy = new BrowserUrlPolicy(host -> new InetAddress[0]);

        BrowserUrlPolicy.ValidationResult result = policy.validateUserUrl("帮我打开学校官网");

        assertThat(result.allowed()).isFalse();
        assertThat(result.message()).contains("http");
    }

    @Test
    void shouldRejectLocalHostWithoutDnsLookup() {
        BrowserUrlPolicy policy = new BrowserUrlPolicy(host -> {
            throw new AssertionError("localhost 不应进入 DNS 查询");
        });

        BrowserUrlPolicy.ValidationResult result = policy.validateUserUrl("打开 https://localhost:8080");

        assertThat(result.allowed()).isFalse();
        assertThat(result.message()).contains("本机");
    }

    @Test
    void shouldRejectPrivateAddressResolvedFromPublicLookingHost() throws Exception {
        BrowserUrlPolicy policy = new BrowserUrlPolicy(host -> new InetAddress[]{
                InetAddress.getByName("192.168.1.8")
        });

        BrowserUrlPolicy.ValidationResult result = policy.validateUserUrl("打开 https://public-looking.example");

        assertThat(result.allowed()).isFalse();
        assertThat(result.message()).contains("内网");
    }
}
