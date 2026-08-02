package com.fourth.ykd.ai.memory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 创建长期记忆后台任务专用线程池。
 * 记忆提取需要再次调用大模型，耗时可能达到数秒，因此不能占用微信回复线程。
 */
@Configuration
public class MemoryExecutorConfiguration {

    /**
     * 创建只供普通对话后台记忆形成使用的线程池。
     * 核心线程负责顺序处理大多数任务，队列已满时直接拒绝，
     * 由 MemoryFormationService 记录日志并放弃本次后台记忆，不影响微信回复。
     *
     * @return 已配置完成的记忆任务线程池
     */
    @Bean(name = "memoryExecutor")
    public ThreadPoolTaskExecutor memoryExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        //统一线程名前缀，便于从日志区分后台记忆任务和微信回复线程。
        executor.setThreadNamePrefix("memory-");
        //大多数记忆任务顺序处理，积压时最多扩展到两个线程。
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        //限制待处理任务数量，防止模型服务变慢时无限占用内存。
        executor.setQueueCapacity(100);

        //队列已满时抛出 RejectedExecutionException，由提交服务降级并记录日志。
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy());

        //项目正常关闭时最多等待十五秒，让已经开始的记忆任务完成。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);

        return executor;
    }

    @Bean(name = "memoryManagementExecutor")
    public ThreadPoolTaskExecutor memoryManagementExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("memory-management-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }
    @Bean(name = "memoryIndexExecutor")
    public ThreadPoolTaskExecutor memoryIndexExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("memory-index-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
