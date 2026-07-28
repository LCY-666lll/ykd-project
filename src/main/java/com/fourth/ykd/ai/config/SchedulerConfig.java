package com.fourth.ykd.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

//配置任务调度器
@Configuration
public class SchedulerConfig {
    @Bean
    public TaskScheduler taskScheduler(){
        //创建一个线程池
        ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
        //设置线程池大小
        scheduler.setPoolSize(10);
        //设置线程池的名字
        scheduler.setThreadNamePrefix("scheduler");
        //初始化线程池
        scheduler.initialize();
        return scheduler;
    }
}
