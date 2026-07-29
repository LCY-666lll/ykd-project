package com.fourth.ykd.ilink.client;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/*
 管理全项目唯一 ILinkClient：创建、获取、关闭。它是项目和 iLink SDK 的连接持有者。
创建 ILinkClient
保存当前唯一的 ILinkClient
给其他业务类查询客户端
重新登录时关闭旧客户端
项目关闭时释放客户端资源
*/
@Slf4j
/*这个类交给Spring管理，在项目启动时创建一个IlinkClientManager对象放进Spring容器内
用了 @Component 后，各个服务注入的是同一个管理器。*/
@Component
public class IlinkClientManager {

    private final ILinkConfig iLinkSdkConfig;

    public IlinkClientManager(ILinkConfig iLinkSdkConfig) {
        this.iLinkSdkConfig = iLinkSdkConfig;
    }

    /**
     * 当前唯一的 iLink 客户端:
     * volatile 的作用：
     * 后续“消息接收线程”和“登录接口线程”在不同线程运行时，
     * 一个线程替换客户端后，另一个线程能立刻看到最新引用。
     */
    private volatile ILinkClient client;

    /**
     * 创建一个新的 iLink 客户端。
     * 使用场景：
     * 用户主动重新发起扫码登录时。
     * 创建前必须先关闭旧客户端，
     * 否则旧客户端的线程池、登录状态和消息游标会残留。
     * volatile 只保证单次读取、写入的可见性，不能保证多个操作组合起来是线程安全的,
     * 所以创建和关闭方法还需要加 synchronized。

     * createNewClient(),加了 synchronized，而：
     * closeCurrentClient(),也加了 synchronized。
     * 但是不会产生死锁： Java 的 synchronized 是 可重入锁。
     * 当前线程已经拿到了 this 对象的锁，还可以再次进入同一个对象上的其他 synchronized 方法。
     */
    public synchronized ILinkClient createNewClient() {
        closeCurrentClient();

        ILinkClient newClient = ILinkClient.builder()
                .config(iLinkSdkConfig)
                .build();

       /*保存为当前客户端：左边：this.client
        表示当前 IlinkClientManager 对象保存的成员变量。
        右边：newClient
        表示刚刚创建出来的局部变量。*/
        this.client = newClient;

        log.info("[iLink] new client created");

        return newClient;
    }
    /**
     * 查询当前客户端是否存在：：“客户端存在”不等于“已经登录”。
     * 是否登录要在下一步通过 current.isLoggedIn() 判断。
     * Optional<>:返回的不是直接的 ILinkClient，而是一个可能有值、也可能没值的包装对象
     */
    public Optional<ILinkClient> findClient() {
        /*如果 client 不为 null,返回一个有值的 Optional
        如果 client 为 null,返回一个空 Optional*/
        return Optional.ofNullable(client);
    }

    /**
     * 关闭并清空当前客户端。加锁：创建和关闭都需要串行执行
     * 使用场景：取消扫码、重新扫码、应用关闭。
     */
    public synchronized void closeCurrentClient() {
        /*保存旧的客户端:左边：current
        是当前方法里的局部变量。
        右边：this.client
        是管理器对象里的成员变量*/
        ILinkClient current = this.client;
        //清空成员变量：当前管理器不再对外提供这个客户端
        this.client = null;
        if (current != null) {
            try {
                current.close();
                log.info("[iLink] client closed");
            } catch (Exception exception) {
                log.warn("[iLink] client close failed: {}", exception.getMessage());
            }
        }
    }

    /**
     * 发送文本消息（提醒推送等主动场景），携带 contextToken 以恢复 iLink 会话上下文。
     * <p>
     * contextToken 从用户消息中提取并持久化，避免 SDK 内部缓存失效导致推送失败。
     * 当前 SDK sendText 不直接接受 contextToken 参数，contextToken 仅用于日志追踪；
     * 若后续 SDK 升级支持 sendText(userId, text, contextToken)，可替换此处调用。
     */
    public void sendText(String userId, String text, String contextToken) {
        findClient().ifPresent(client -> {
            try {
                client.sendText(userId, text);
                log.info("[iLink][SEND_WITH_CONTEXT] userId={}, hasContextToken={}, textLength={}",
                        userId, contextToken != null, text.length());
            } catch (IOException e) {
                log.error("[iLink][SEND_WITH_CONTEXT_FAILED] userId={}, contextToken={}",
                        userId, contextToken != null ? contextToken.substring(0, Math.min(8, contextToken.length())) + "..." : "null", e);
            }
        });
        if (findClient().isEmpty()) {
            log.warn("[iLink][SEND_WITH_CONTEXT_SKIPPED] userId={}, reason=NO_ILINK_CLIENT", userId);
        }
    }

    /**
     * @PreDestroy 表示：Spring 容器准备销毁这个 Bean 前，自动调用这个方法。
     * Spring Boot 停止时自动调用。防止 SDK 线程池遗留，导致项目无法正常结束。
     */
    @PreDestroy
    public void shutdown() {
        closeCurrentClient();
    }
}