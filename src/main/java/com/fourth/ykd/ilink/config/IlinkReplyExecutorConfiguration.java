package com.fourth.ykd.ilink.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class IlinkReplyExecutorConfiguration {

    @Bean(name = "iLinkReplyExecutor")
    public ThreadPoolTaskExecutor iLinkReplyExecutor(
            IlinkProperties properties
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setThreadNamePrefix("ilink-reply-");
        executor.setCorePoolSize(properties.getReplyCoreThreads());
        executor.setMaxPoolSize(properties.getReplyMaxThreads());
        executor.setQueueCapacity(properties.getReplyQueueCapacity());

        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy()
        );

        /*应用准备关闭
           ↓
        等待线程池已有任务完成
           ↓
        最多等 15 秒
           ↓
        超过时间后继续关闭*/
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);

        return executor;
    }
}