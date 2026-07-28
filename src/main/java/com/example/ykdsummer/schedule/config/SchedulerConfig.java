package com.example.ykdsummer.schedule.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 定时任务线程池配置。
 *
 * <p>核心功能：
 * <ul>
 *   <li>使用独立的线程池执行定时任务，与 Spring {@code @Scheduled} 线程池隔离</li>
 *   <li>启用优雅关闭：等待进行中的任务完成，最多等待 30 秒</li>
 *   <li>线程名前缀 "scheduled-task-"，便于日志和监控识别</li>
 * </ul>
 */
@Configuration
public class SchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfig.class);

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

        // 核心线程数：足够覆盖日常定时任务（每日推送、定时提醒等）
        scheduler.setPoolSize(10);
        scheduler.setThreadNamePrefix("scheduled-task-");
        scheduler.setDaemon(false);

        // === 优雅关闭 ===
        // 等待正在执行的任务完成后再关闭线程池
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        // 最多等待 30 秒，超时后强制关闭
        scheduler.setAwaitTerminationSeconds(30);

        // 线程池满时由调用线程执行，降低任务丢弃风险
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        scheduler.initialize();
        log.info("TaskScheduler initialized: poolSize=10, gracefulShutdown=true, awaitTermination=30s");
        return scheduler;
    }
}
