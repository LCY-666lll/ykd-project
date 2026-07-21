package com.fourth.ykd.ilink.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/*读取 ilink.* 配置：是否启用、超时、轮询间隔、线程池大小、队列容量等：
Spring 启动
  ↓
读取 application.properties
  ↓
创建 IlinkProperties
  ↓
调用 iLinkSdkConfig(...)
  ↓
创建并保存一个 SDK ILinkConfig
  ↓
后续 IlinkClientManager 使用它创建 ILinkClient*/
@Getter
@Setter
@ConfigurationProperties(prefix = "ilink")
public class IlinkProperties {

    /**
     * 是否启用 iLink 模块。
     * 以后临时排查其他功能时，可以设为 false，避免启动 iLink。
     */
    private boolean enabled = true;

    /**
     * 手机扫码登录成功后，本地保存会话凭证的文件。
     * 重启项目时可尝试恢复登录，不必每次重新扫码。
     */
    private String sessionFile = ".ilink/session.token";

    /**
     * 与 iLink 服务建立 TCP/HTTP 连接的最长等待时间。
     */
    private long connectTimeoutMs = 5_000;

    /**
     * 接收 iLink 服务响应的最长等待时间。
     * 后续收消息采用长轮询，所以不能设置得过短。
     */
    private long readTimeoutMs = 35_000;

    /**
     * 向 iLink 服务发送请求数据的最长等待时间。
     */
    private long writeTimeoutMs = 5_000;

    /**
     * 单个 iLink HTTP 请求失败后的最大重试次数。
     */
    private int httpMaxRetries = 2;

    /**
     * 第一次重试前的基础等待时间。
     */
    private long retryBaseDelayMs = 300;

    /**
     * 重试等待时间的最大值，防止重试越等越久。
     */
    private long retryMaxDelayMs = 2_000;

    /**
     * 是否开启 SDK 内置心跳。
     * 先关闭；第 4 步我们会自己实现更及时的消息接收循环。
     */
    private boolean heartbeatEnabled = false;

    /**
     * SDK 内部处理网络 IO 的核心线程数。
     */
    private int ioCoreThreads = 2;

    /**
     * SDK 内部处理网络 IO 的最大线程数。
     */
    private int ioMaxThreads = 4;

    /**
     * SDK 内部定时任务线程数。
     */
    private int schedulerThreads = 1;

    /**
     * SDK 内部任务队列容量，防止任务无限积压。
     */
    private int queueCapacity = 100;

    /**
     * SDK 自动重连开关。
     */
    private boolean autoReconnectEnabled = true;

    /**
     * 两次消息拉取之间的最小等待时间。
     * 无消息时：getUpdates() 长时间等待 → 不会疯狂请求
     * 有消息、接口立即返回时：等待 500ms 再请求
     * → 控制台通常在 0.5 秒级别看到新消息→ 不会无间隔占满 CPU
     */
    private long pollDelayMs = 500;

    // Application reply worker settings; separate from the SDK IO pool.
    private int replyCoreThreads = 1;

    private int replyMaxThreads = 1;

    private int replyQueueCapacity = 100;
}