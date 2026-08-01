package com.example.ykdsummer.ai.tool;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 把 Tool 后台线程中的完成信号转交给 Spring 事件监听器。 */
@Component
public class SpringImageTaskCompletionPublisher implements ImageTaskCompletionPublisher {
    private final ApplicationEventPublisher publisher;

    public SpringImageTaskCompletionPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(ImageTaskCompletionEvent event) {
        if (event != null && event.imageBytes().length > 0) {
            publisher.publishEvent(event);
        }
    }
}
