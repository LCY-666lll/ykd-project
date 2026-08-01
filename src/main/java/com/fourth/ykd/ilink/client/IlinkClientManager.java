package com.fourth.ykd.ilink.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final String sessionFilePath;

    public IlinkClientManager(ILinkConfig iLinkSdkConfig,
                              com.fourth.ykd.ilink.config.IlinkProperties properties) {
        this.iLinkSdkConfig = iLinkSdkConfig;
        this.sessionFilePath = properties.getSessionFile();
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
     * 尝试从本地会话文件恢复登录状态，避免每次重启都要扫码。
     * @return true 表示恢复成功，false 表示需要重新扫码
     */
    @SuppressWarnings("unchecked")
    public synchronized boolean restoreFromSession() {
        // 1. 检查文件是否存在
        Path path = Path.of(sessionFilePath);
        if (!Files.exists(path)) {
            log.info("[iLink] no session file found at {}", sessionFilePath);
            return false;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> map = mapper.readValue(path.toFile(), Map.class);

            String botToken = (String) map.get("botToken");
            String userId = (String) map.get("userId");
            String botId = (String) map.get("botId");
            String baseUrl = (String) map.get("baseUrl");
            String updatesCursor = (String) map.get("updatesCursor");
            // 4. 构建恢复上下文
            LoginContext loginContext = new LoginContext(botToken, userId, botId, baseUrl);

            ResumeContext.Builder builder = ResumeContext.builder(loginContext);
            if (updatesCursor != null) {
                builder.updatesCursor(updatesCursor);
            }

            ResumeContext resumeContext = builder.build();

            closeCurrentClient();
// 5. 用恢复上下文创建客户端（自动登录）
            ILinkClient newClient = ILinkClient.builder()
                    .config(iLinkSdkConfig)
                    .resumeContext(resumeContext)
                    .build();

            this.client = newClient;
            log.info("[iLink] session restored from {}", sessionFilePath);
            return true;
        } catch (Exception e) {
            log.warn("[iLink] failed to restore session: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 保存当前登录会话到本地文件，供下次启动恢复使用。
     * 使用 SDK 的 exportResumeContext() 导出完整上下文。
     */
    public synchronized void saveSession() {
        if (client == null) {
            log.warn("[iLink] saveSession skipped: client is null");
            return;
        }
        if (!client.isLoggedIn()) {
            log.warn("[iLink] saveSession skipped: client.isLoggedIn()=false");
            return;
        }

        try {
            ResumeContext resumeContext = client.exportResumeContext();
            if (resumeContext == null) {
                log.warn("[iLink] saveSession failed: exportResumeContext() returned null");
                return;
            }
            LoginContext ctx = resumeContext.getLoginContext();
            if (ctx == null) {
                log.warn("[iLink] saveSession failed: getLoginContext() returned null");
                return;
            }
            log.info("[iLink] saving session: botId={}, userId={}, baseUrl={}", ctx.getBotId(), ctx.getUserId(), ctx.getBaseUrl());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("botToken", ctx.getBotToken());
            map.put("userId", ctx.getUserId());
            map.put("botId", ctx.getBotId());
            map.put("baseUrl", ctx.getBaseUrl());
            map.put("updatesCursor", resumeContext.getUpdatesCursor());

            ObjectMapper mapper = new ObjectMapper();
            Path path = Path.of(sessionFilePath);
            Files.createDirectories(path.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), map);
            log.info("[iLink] session saved to {}", sessionFilePath);
        } catch (Exception e) {
            log.warn("[iLink] failed to save session: {}", e.getMessage());
        }
    }

    /**
     * 直接使用 loginContext 保存会话，绕过 client.isLoggedIn() 检查。
     * 用于登录成功回调时，SDK 内部状态可能还未更新的情况。
     */
    public synchronized void saveLoginContext(LoginContext ctx) {
        if (ctx == null) {
            log.warn("[iLink] saveLoginContext skipped: loginContext is null");
            return;
        }

        try {
            log.info("[iLink] saving loginContext: botId={}, userId={}, baseUrl={}", ctx.getBotId(), ctx.getUserId(), ctx.getBaseUrl());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("botToken", ctx.getBotToken());
            map.put("userId", ctx.getUserId());
            map.put("botId", ctx.getBotId());
            map.put("baseUrl", ctx.getBaseUrl());
            map.put("updatesCursor", null);

            ObjectMapper mapper = new ObjectMapper();
            Path path = Path.of(sessionFilePath);
            Files.createDirectories(path.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), map);
            log.info("[iLink] session saved to {}", sessionFilePath);
        } catch (Exception e) {
            log.warn("[iLink] failed to save loginContext: {}", e.getMessage());
        }
    }

    /**
     * 查询当前客户端是否存在：：”客户端存在”不等于”已经登录”。
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
     * @PreDestroy 表示：Spring 容器准备销毁这个 Bean 前，自动调用这个方法。
     * Spring Boot 停止时自动调用。防止 SDK 线程池遗留，导致项目无法正常结束。
     */
    @PreDestroy
    public void shutdown() {
        closeCurrentClient();
    }
}