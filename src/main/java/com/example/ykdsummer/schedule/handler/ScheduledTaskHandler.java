package com.example.ykdsummer.schedule.handler;

import com.example.ykdsummer.schedule.model.ScheduledTask;

/**
 * 定时任务处理器接口。
 *
 * <p>所有需要被定时调度执行的业务逻辑都应实现此接口，
 * 并注册为 Spring Bean。任务定义中的 {@code handlerBean} 字段对应 Bean 名称。
 *
 * <p>示例实现：
 * <pre>{@code
 * @Component("dailyWeatherPush")
 * public class DailyWeatherPushHandler implements ScheduledTaskHandler {
 *     public void execute(ScheduledTask task) {
 *         // 读取 task.getParams() 中的 JSON 参数
 *         // 执行推送逻辑
 *     }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ScheduledTaskHandler {

    /**
     * 执行定时任务。
     *
     * @param task 任务实体，包含名称、参数等元信息
     */
    void execute(ScheduledTask task);
}
