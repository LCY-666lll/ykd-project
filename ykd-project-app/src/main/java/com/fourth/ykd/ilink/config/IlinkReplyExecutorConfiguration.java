package com.fourth.ykd.ilink.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
/*创建 iLinkReplyExecutor 回复线程池。模型调用、发消息都放到这里，不阻塞轮询线程。
线程池解决的是：把耗时的 AI 回复任务从微信消息接收线程中拿出去，
保证机器人在生成 PDF、图片、语音或等待模型时，仍然可以继续接收后续微信消息。

“接收消息”和“处理回复”解耦：
回复任务即使很慢
→ 也只会占用回复线程
→ 不会占用微信消息轮询线程

* 消息轮询线程
→ 只负责收消息、提取消息、提交任务

回复线程
→ 负责意图识别、调用模型、工具调用、生成文件、发送结果

*/
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

        /*应用开始关闭
         → 等待线程池任务完成
         → 最多等待 15 秒
         → 超过后继续关闭流程*/
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);

        return executor;
    }
}