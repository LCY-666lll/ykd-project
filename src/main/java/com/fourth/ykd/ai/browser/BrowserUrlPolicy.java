package com.fourth.ykd.ai.browser;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 对浏览器任务中的用户网址做最小安全校验。*/
@Component
public class BrowserUrlPolicy {

    private static final Pattern HTTP_URL_PATTERN =
            Pattern.compile("(?i)https?://[^\\s<>\"'，。；：、\\u4E00-\\u9FFF]+");

    //域名解析成 IP 地址
    private final HostAddressResolver hostAddressResolver;

    public BrowserUrlPolicy() {
        this(InetAddress::getAllByName);
    }

    BrowserUrlPolicy(HostAddressResolver hostAddressResolver) {
        this.hostAddressResolver = hostAddressResolver;
    }

    public boolean containsExplicitHttpUrl(String userText) {
        return HTTP_URL_PATTERN.matcher(userText == null ? "" : userText).find();
    }

    public ValidationResult validateUserUrl(String userText) {
        Matcher matcher = HTTP_URL_PATTERN.matcher(userText == null ? "" : userText);
        if (!matcher.find()) {
            return ValidationResult.denied("未找到明确的 http 或 https 网址，请先发送完整公开网址。");
        }

        String url = removeTrailingPunctuation(matcher.group());
        try {
            URI uri = new URI(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                return ValidationResult.denied("仅支持 http 或 https 的公开网址。");
            }
            if (uri.getUserInfo() != null || uri.getHost() == null || uri.getHost().isBlank()) {
                return ValidationResult.denied("网址格式不安全或不完整，请发送不含账号信息的公开网址。");
            }

            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (isLocalHost(host)) {
                return ValidationResult.denied("不允许访问本机或内网地址。");
            }
            for (InetAddress address : hostAddressResolver.resolve(host)) {
                if (isPrivateOrLocal(address)) {
                    return ValidationResult.denied("不允许访问解析到内网或本机的地址。");
                }
            }
            return ValidationResult.allowed(uri.toASCIIString());
        } catch (URISyntaxException e) {
            return ValidationResult.denied("网址格式不正确，请发送完整公开网址。");
        } catch (UnknownHostException e) {
            return ValidationResult.denied("网址域名无法解析，请确认网址是否正确后重试。");
        }
    }

    private boolean isLocalHost(String host) {
        return "localhost".equals(host)
                || host.endsWith(".localhost")
                || "0.0.0.0".equals(host)
                || "::1".equals(host);
    }

    private boolean isPrivateOrLocal(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && (bytes[0] & 0xff) == 100
                && (bytes[1] & 0xff) >= 64
                && (bytes[1] & 0xff) <= 127;
    }

    private String removeTrailingPunctuation(String value) {
        return value.replaceAll("[)\\]}>，。；：、！？]+$", "");
    }

    @FunctionalInterface
    interface HostAddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public record ValidationResult(boolean allowed, String url, String message) {
        static ValidationResult allowed(String url) {
            return new ValidationResult(true, url, "");
        }

        static ValidationResult denied(String message) {
            return new ValidationResult(false, null, message);
        }
    }
}
