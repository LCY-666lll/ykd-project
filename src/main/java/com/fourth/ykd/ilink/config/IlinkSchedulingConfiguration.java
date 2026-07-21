package com.fourth.ykd.ilink.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/*开启 Spring @Scheduled，否则轮询微信消息和图片过期清理不会执行：
@Scheduled(...)只有在 Spring 开启 @EnableScheduling 后才会真正定时执行。
没有这个类，即使消息接收器里写了 @Scheduled，方法也永远不会运行*/
@Configuration
@EnableScheduling
public class IlinkSchedulingConfiguration {
}