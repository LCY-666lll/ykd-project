package com.fourth.ykd.ilink.config;

import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/* 转换器：我们的配置 IlinkProperties
        ↓
IlinkSdkConfiguration 转换
        ↓
SDK 配置 ILinkConfig*/
@Configuration
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