package com.fourth.ykd.ai.utils;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

@Component
public class DynamicSchedulerTool {
    private final TaskScheduler taskScheduler;

    public DynamicSchedulerTool(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    /**
     * 按固定延迟执行任务（单位：毫秒）
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delayMs) {
        return taskScheduler.scheduleWithFixedDelay(task,delayMs);
    }

    /**
     * 按 Cron 表达式执行任务
     */
    public ScheduledFuture<?> scheduleCron(Runnable task, String cron) {
        return taskScheduler.schedule(task, new CronTrigger(cron));
    }

    /**
     * 在指定时刻执行一次
     */
    public ScheduledFuture<?> scheduleAt(Runnable task, Instant startTime) {
        return taskScheduler.schedule(task, startTime);
    }

    /**
     * 取消已调度的任务
     */
    public void cancel(ScheduledFuture<?> future) {
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
    }
}
