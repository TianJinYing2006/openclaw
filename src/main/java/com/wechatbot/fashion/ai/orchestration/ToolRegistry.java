package com.wechatbot.fashion.ai.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

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

    /** 返回所有工具的元信息（用于构建 LLM 的 tool description prompt + 风险审计）。 */
    public List<ToolMeta> allToolMeta() {
        if (tools == null) return List.of();
        return tools.values().stream()
                .map(entry -> new ToolMeta(entry.name, entry.description, ToolGovernance.riskOf(entry.name)))
                .toList();
    }

    /** 查询单个工具的治理策略（风险等级 / 输入上限 / 是否需确认）。 */
    public ToolPolicy policyFor(String toolName) {
        return ToolGovernance.policyOf(toolName);
    }

    /**
     * 生成“当前可用工具”能力声明段，注入 system prompt。
     *
     * <p>能力边界由工具集自动推导，避免在 prompt 中手写“有哪些/没有哪些能力”
     * 与 {@code @AgentTool} 注册结果漂移（如工具已注册但 prompt 仍声称没有该能力）。</p>
     */
    public String capabilityDeclaration() {
        List<ToolMeta> metas = allToolMeta();
        if (metas.isEmpty()) {
            return "";
        }
        String names = metas.stream().map(ToolMeta::name).collect(Collectors.joining("、"));
        return "当前可用的工具：" + names + "。除此之外没有文档、语音、飞书、娱乐或通用信息查询能力，不要假装可以完成。";
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

    /** 工具分组：核心（始终提供）。 */
    public static final String GROUP_CORE = "core";
    /** 工具分组：衣橱查看/检索。 */
    public static final String GROUP_WARDROBE_VIEW = "wardrobe_view";
    /** 工具分组：衣橱入库/抠图/管理。 */
    public static final String GROUP_WARDROBE_INTAKE = "wardrobe_intake";
    /** 工具分组：虚拟试穿/人物模板。 */
    public static final String GROUP_TRYON = "tryon";
    /** 工具分组：微信定时提醒。 */
    public static final String GROUP_REMINDER = "reminder";

    /** 非核心工具归属分组；未列出的工具默认归属核心（始终可用）。 */
    private static final Map<String, String> TOOL_GROUP = Map.ofEntries(
            Map.entry("search_wardrobe", GROUP_WARDROBE_VIEW),
            Map.entry("search_wardrobe_semantic", GROUP_WARDROBE_VIEW),
            Map.entry("get_fashion_profile", GROUP_WARDROBE_VIEW),
            Map.entry("show_wardrobe_items", GROUP_WARDROBE_VIEW),
            Map.entry("select_wardrobe_preview_item", GROUP_WARDROBE_VIEW),
            Map.entry("add_wardrobe_item", GROUP_WARDROBE_INTAKE),
            Map.entry("delete_wardrobe_item", GROUP_WARDROBE_INTAKE),
            Map.entry("purge_wardrobe_item", GROUP_WARDROBE_INTAKE),
            Map.entry("analyze_wardrobe_photo", GROUP_WARDROBE_INTAKE),
            Map.entry("submit_garment_cutout", GROUP_WARDROBE_INTAKE),
            Map.entry("list_garment_draft_versions", GROUP_WARDROBE_INTAKE),
            Map.entry("preview_garment_draft_version", GROUP_WARDROBE_INTAKE),
            Map.entry("retry_garment_cutout", GROUP_WARDROBE_INTAKE),
            Map.entry("edit_garment_draft", GROUP_WARDROBE_INTAKE),
            Map.entry("update_wardrobe_candidate_labels", GROUP_WARDROBE_INTAKE),
            Map.entry("confirm_wardrobe_candidate", GROUP_WARDROBE_INTAKE),
            Map.entry("cancel_wardrobe_candidate", GROUP_WARDROBE_INTAKE),
            Map.entry("list_wardrobe_photo_candidates", GROUP_WARDROBE_INTAKE),
            Map.entry("virtual_try_on_reference_outfit", GROUP_TRYON),
            Map.entry("virtual_try_on_wardrobe_item", GROUP_TRYON),
            Map.entry("save_person_tryon_template", GROUP_TRYON),
            Map.entry("select_person_tryon_template", GROUP_TRYON),
            Map.entry("list_person_tryon_templates", GROUP_TRYON),
            Map.entry("show_current_tryon_template", GROUP_TRYON),
            Map.entry("create_scheduled_agent_task", GROUP_REMINDER),
            Map.entry("list_wechat_reminders", GROUP_REMINDER),
            Map.entry("cancel_wechat_reminder", GROUP_REMINDER)
    );

    /**
     * 按分组返回工具 Bean（用于按意图裁剪工具子集）。
     *
     * <p>核心组工具（含未分组工具）始终提供；非核心工具仅当所属分组被选中时提供。
     * 裁剪后模型本轮只能调用这些工具，从源头降低"38 个工具决策负担"导致的漏调/错调。</p>
     */
    public Object[] toolBeansForGroups(Set<String> groups) {
        if (tools == null) {
            log.warn("toolBeansForGroups() called but tools not initialized yet");
            return new Object[0];
        }
        if (groups == null || groups.isEmpty()) {
            return allToolBeans();
        }
        Object[] beans = tools.values().stream()
                .filter(entry -> {
                    String group = TOOL_GROUP.getOrDefault(entry.name, GROUP_CORE);
                    return GROUP_CORE.equals(group) || groups.contains(group);
                })
                .map(ToolEntry::bean)
                .distinct()
                .toArray();
        log.debug("toolBeansForGroups({}) returning {} beans for {} tool entries",
                groups, beans.length, tools.size());
        return beans;
    }

    /** 按分组返回工具名列表（用于构建本轮能力声明）。 */
    public List<String> toolNamesForGroups(Set<String> groups) {
        if (tools == null) {
            return List.of();
        }
        if (groups == null || groups.isEmpty()) {
            return allToolMeta().stream().map(ToolMeta::name).toList();
        }
        return tools.values().stream()
                .filter(entry -> {
                    String group = TOOL_GROUP.getOrDefault(entry.name, GROUP_CORE);
                    return GROUP_CORE.equals(group) || groups.contains(group);
                })
                .map(ToolEntry::name)
                .toList();
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

    /** 工具的元信息（用于构建 Prompt / 观测）。risk 由 {@link ToolGovernance} 集中声明。 */
    public record ToolMeta(String name, String description, ToolRisk risk) {}
}
