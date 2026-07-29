package com.fourth.ykd.ilink.config;

import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/*把项目自己的 IlinkProperties 转成 SDK 接口能接收的 ILinkConfig。：
项目配置 IlinkProperties
        ↓
IlinkSdkConfiguration 逐项转换
        ↓
SDK 配置 ILinkConfig
        ↓
ILinkClient.builder().config(...)*/
@Configuration
/*注册 IlinkProperties
→ 读取 ilink.*
→ 完成字段绑定
→ 放入 Spring 容器*/
@EnableConfigurationProperties(IlinkProperties.class)
public class IlinkSdkConfiguration {

    @Bean
    public ILinkConfig iLinkSdkConfig(IlinkProperties properties) {
        return ILinkConfig.builder()
                .connectTimeoutMs(properties.getConnectTimeoutMs())
                .readTimeoutMs(properties.getReadTimeoutMs())
                .writeTimeoutMs(properties.getWriteTimeoutMs())
                .httpMaxRetries(properties.getHttpMaxRetries())
                .retryBaseDelayMs(properties.getRetryBaseDelayMs())
                .retryMaxDelayMs(properties.getRetryMaxDelayMs())

                //解决多个请求同时失败后又同时重试的问题：随机抖动会让它们的重试时间稍微错开。
                .retryJitterEnabled(true)

                .heartbeatEnabled(properties.isHeartbeatEnabled())
                .ioCoreThreads(properties.getIoCoreThreads())
                .ioMaxThreads(properties.getIoMaxThreads())
                .schedulerThreads(properties.getSchedulerThreads())
                .queueCapacity(properties.getQueueCapacity())
                .autoReconnectEnabled(properties.isAutoReconnectEnabled())
                .build();
    }
}