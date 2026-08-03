package com.fourth.ykd.ilink.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 读取 liepin.* 配置：Cookie、是否无头模式等。
 * 凭证放在 application-local.properties（已 gitignore），不要提交到仓库。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "liepin")
public class
LiepinProperties {

    /**
     * 猎聘登录 Cookie，从浏览器获取：
     * 登录 liepin.com → F12 → Console → document.cookie → 复制
     */
    private String cookie = "";

    /**
     * 是否使用无头模式（不显示浏览器窗口）。
     * 开发调试时建议 false，生产环境建议 true。
     */
    private boolean headless = false;

    /**
     * 页面操作超时时间（毫秒）。
     */
    private long timeoutMs = 15_000;

    /**
     * 操作间随机延迟最小值（毫秒），模拟人类操作。
     */
    private long delayMinMs = 500;

    /**
     * 操作间随机延迟最大值（毫秒）。
     */
    private long delayMaxMs = 2_000;

    /**
     * 默认搜索城市，用户未指定时使用。
     */
    private String defaultCity = "";

    /**
     * 默认搜索关键词，用户未指定时使用。
     */
    private String defaultKeyword = "";
}