package com.example.ykdsummer.ai.orchestration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 {@link org.springframework.ai.tool.annotation.Tool} 方法可被 Agent 模型调用。
 *
 * <p>可标注在类或方法上，方法级注解优先于类级注解。
 * <ul>
 *   <li>类级 {@code @AgentTool}：该类所有 {@code @Tool} 方法默认可被 Agent 调用。</li>
 *   <li>方法级 {@code @AgentTool(enabled = false)}：即使类级标记为 enabled，该方法也被排除。</li>
 *   <li>无 {@code @AgentTool}：该方法不可被 Agent 调用（默认拒绝）。</li>
 * </ul>
 *
 * <p>新增工具时只需在工具类或方法上添加此注解，无需修改 {@link ToolRegistry}。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {

    /**
     * 设为 false 可排除特定工具方法，即使所在类已标记 {@code @AgentTool}。
     */
    boolean enabled() default true;
}
