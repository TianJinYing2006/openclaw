package com.example.ykdsummer.ai.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 微信服装 Agent 的 Tool 注册表。
 *
 * <p>容器内可能仍有历史功能的 Bean，但不能因为标了 {@code @Tool} 就自动交给模型。
 * 这里采用默认拒绝，只暴露标有 {@link AgentTool} 注解的工具方法，避免聊天模型获得
 * 飞书、娱乐、财经、旅游等与服装主线无关的能力。</p>
 *
 * <p>在 {@link ContextRefreshedEvent} 事件中扫描，确保所有 Bean 均已初始化完成，
 * 避免循环依赖。</p>
 */
@Component
public class ToolRegistry implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ApplicationContext applicationContext;
    private Map<String, ToolEntry> tools = new LinkedHashMap<>();

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 只处理 root context（避免重复扫描）
        if (event.getApplicationContext() != applicationContext) {
            return;
        }
        this.tools = new LinkedHashMap<>();
        collectTools(applicationContext);
    }

    /** 返回所有工具的元信息（用于构建 LLM 的 tool description prompt）。 */
    public List<ToolMeta> allToolMeta() {
        if (tools == null) return List.of();
        return tools.values().stream()
                .map(entry -> new ToolMeta(entry.name, entry.description))
                .toList();
    }

    /** 根据工具名查找对应的 Bean 和方法。 */
    public Optional<ToolEntry> find(String actionName) {
        if (tools == null) return Optional.empty();
        return Optional.ofNullable(tools.get(actionName));
    }

    /** 获取所有工具 Bean（用于 Spring AI .tools() 注册）。 */
    public Object[] allToolBeans() {
        if (tools == null) {
            log.warn("allToolBeans() called but tools not initialized yet");
            return new Object[0];
        }
        Object[] beans = tools.values().stream()
                .map(ToolEntry::bean)
                .distinct()
                .toArray();
        log.debug("allToolBeans() returning {} beans for {} tool entries",
                beans.length, tools.size());
        return beans;
    }

    private void collectTools(ApplicationContext context) {
        String[] beanNames = context.getBeanDefinitionNames();
        for (String name : beanNames) {
            Object bean = context.getBean(name);
            Class<?> beanClass = bean.getClass();
            AgentTool classLevel = beanClass.getAnnotation(AgentTool.class);
            for (Method method : beanClass.getMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation != null) {
                    String toolName = annotation.name();
                    if (toolName.isBlank()) {
                        toolName = method.getName();
                    }
                    // 方法级 @AgentTool 优先于类级；无注解则默认拒绝
                    AgentTool methodLevel = method.getAnnotation(AgentTool.class);
                    AgentTool effective = methodLevel != null ? methodLevel : classLevel;
                    if (effective == null || !effective.enabled()) {
                        log.debug("Skipped non-agent tool: {} ({})", toolName, beanClass.getSimpleName());
                        continue;
                    }
                    String description = annotation.description();
                    tools.put(toolName, new ToolEntry(toolName, description, bean, method));
                    log.debug("Registered tool: {} ({})", toolName, beanClass.getSimpleName());
                }
            }
        }
        log.info("ToolRegistry collected {} tool(s): {}", tools.size(), tools.keySet());
    }

    /** 单个工具的注册条目。 */
    public record ToolEntry(String name, String description, Object bean, Method method) {}

    /** 工具的元信息（用于构建 Prompt）。 */
    public record ToolMeta(String name, String description) {}
}
