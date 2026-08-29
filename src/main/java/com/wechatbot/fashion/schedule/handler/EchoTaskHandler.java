package com.wechatbot.fashion.schedule.handler;

import com.wechatbot.fashion.schedule.model.ScheduledTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 示例定时任务处理器：在日志中打印任务信息。
 *
 * <p>可用于测试调度器是否正常工作。创建任务时设置 {@code handlerBean = "echoTaskHandler"}。
 */
@Component("echoTaskHandler")
public class EchoTaskHandler implements ScheduledTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(EchoTaskHandler.class);

    @Override
    public void execute(ScheduledTask task) {
        log.info("Echo task executed: id={}, name='{}', params={}",
                task.getId(), task.getName(), task.getParams());
    }
}
