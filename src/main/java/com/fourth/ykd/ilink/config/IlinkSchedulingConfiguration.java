package com.fourth.ykd.ilink.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/*开启 Spring @Scheduled，否则轮询微信消息和图片过期清理不会执行：
@Scheduled(...)只有在 Spring 开启 @EnableScheduling 后才会真正定时执行。
没有这个类，即使消息接收器里写了 @Scheduled，方法也永远不会运行
询问 iLink 服务：
现在有没有新的微信消息？
因此必须不断重复：
调用 getUpdates()
→ 处理返回消息
→ 等待一小段时间
→ 再次调用 getUpdates()
*/
@Configuration
/*扫描 Spring Bean 中的 @Scheduled 方法
→ 注册定时任务
→ 项目运行后按照配置周期执行*/
@EnableScheduling
public class IlinkSchedulingConfiguration {
}