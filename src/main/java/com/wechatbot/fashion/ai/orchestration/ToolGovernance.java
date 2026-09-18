package com.wechatbot.fashion.ai.orchestration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具治理策略中心：集中声明每个工具的风险等级、输入上限与确认要求。
 *
 * <p>采用「集中式策略表」而非散落到各工具类，原因：
 * <ol>
 *   <li>评审时一屏可审完所有高风险工具；</li>
 *   <li>未声明的工具默认只读，越权需要显式声明（默认拒绝思路）；</li>
 *   <li>{@link BoundedToolCallingManager} 可在调用前用 {@link #policyOf(String)} 做输入上限校验，
 *       无需依赖 Spring 注入，避免初始化顺序问题。</li>
 * </ol>
 */
public final class ToolGovernance {

    private static final Map<String, ToolPolicy> POLICIES = buildPolicies();

    private ToolGovernance() {
    }

    /** 查询工具策略；未声明按只读处理；timeout/调用上限缺失时按风险等级补默认值。 */
    public static ToolPolicy policyOf(String toolName) {
        ToolPolicy base = toolName == null
                ? ToolPolicy.readOnly()
                : POLICIES.getOrDefault(toolName, ToolPolicy.readOnly());
        return base.withRiskDefaults(defaultTimeoutMillis(base.risk()), defaultMaxCallsPerRun(base.risk()));
    }

    /** 风险等级默认单次调用超时。 */
    public static long defaultTimeoutMillis(ToolRisk risk) {
        return switch (risk) {
            case READ_ONLY -> 20_000L;
            case EXTERNAL_DATA -> 30_000L;
            case USER_DATA -> 60_000L;
            case PAID_OPERATION -> 200_000L;
            case SIDE_EFFECT -> 30_000L;
        };
    }

    /** 风险等级默认单 run 调用上限（全局上限另由 AgentBudgetProperties.maxToolCallsPerRun 约束）。 */
    public static int defaultMaxCallsPerRun(ToolRisk risk) {
        return switch (risk) {
            case READ_ONLY -> 20;
            case EXTERNAL_DATA -> 10;
            case USER_DATA -> 10;
            case PAID_OPERATION -> 5;
            case SIDE_EFFECT -> 10;
        };
    }

    public static ToolRisk riskOf(String toolName) {
        return policyOf(toolName).risk();
    }

    /** 全部高风险管理工具（隐私 / 费用 / 副作用），供管理站与评测审计。 */
    public static Set<String> highRiskTools() {
        return POLICIES.entrySet().stream()
                .filter(e -> e.getValue().risk().isHighRisk())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    /** 需要用户确认的工具。 */
    public static Set<String> confirmationRequiredTools() {
        return POLICIES.entrySet().stream()
                .filter(e -> e.getValue().requiresConfirmation())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    /** 只读快照（含风险默认值，保持声明顺序），供观测/文档。 */
    public static Map<String, ToolPolicy> allPolicies() {
        Map<String, ToolPolicy> resolved = new LinkedHashMap<>();
        POLICIES.forEach((name, policy) -> resolved.put(name, policyOf(name)));
        return Collections.unmodifiableMap(resolved);
    }

    private static Map<String, ToolPolicy> buildPolicies() {
        Map<String, ToolPolicy> m = new LinkedHashMap<>();

        // ---- 只读：公开/自身数据 ----
        addReadOnly(m,
                "get_fashion_profile", "search_wardrobe", "search_wardrobe_semantic",
                "show_wardrobe_items", "show_current_tryon_template", "select_wardrobe_preview_item",
                "list_person_tryon_templates", "list_wardrobe_photo_candidates",
                "list_garment_draft_versions", "preview_garment_draft_version",
                "list_recent_images", "get_current_image",
                "list_wechat_reminders", "search_fashion_references",
                "recommend_outfits_from_wardrobe", "check_virtual_tryon_status");
        // 输入有界的只读查询：限制模型可能生成的长参数，防止参数滥用
        addLimited(m, ToolRisk.READ_ONLY, 500, "get_current_weather");
        addLimited(m, ToolRisk.READ_ONLY, 100, "get_current_china_time");

        // ---- 外部数据（不可信，Prompt Injection 边界）----
        addLimited(m, ToolRisk.EXTERNAL_DATA, 2000, "search_web");
        add(m, ToolRisk.EXTERNAL_DATA, "inspect_image");

        // ---- 用户隐私数据 ----
        add(m, ToolRisk.USER_DATA,
                "analyze_wardrobe_photo", "submit_garment_cutout", "retry_garment_cutout",
                "edit_garment_draft", "save_person_tryon_template", "select_person_tryon_template",
                "update_wardrobe_candidate_labels");

        // ---- 付费算力 ----
        add(m, ToolRisk.PAID_OPERATION, "generate_image", "create_image_revision");
        // 虚拟试衣消耗付费算力，执行前需用户确认（HITL 工具级闸门）
        addConfirm(m, ToolRisk.PAID_OPERATION,
                "virtual_try_on_wardrobe_item", "virtual_try_on_reference_outfit");

        // ---- 外部副作用 ----
        add(m, ToolRisk.SIDE_EFFECT,
                "add_wardrobe_item", "confirm_wardrobe_candidate", "restore_image_version");
        // 破坏性操作：标记需确认
        addConfirm(m, ToolRisk.SIDE_EFFECT,
                "delete_wardrobe_item", "purge_wardrobe_item", "cancel_wardrobe_candidate",
                "create_scheduled_agent_task", "cancel_wechat_reminder",
                "forget_fashion_preference", "clear_fashion_profile");

        return m;
    }

    private static void addReadOnly(Map<String, ToolPolicy> m, String... names) {
        add(m, ToolRisk.READ_ONLY, names);
    }

    private static void add(Map<String, ToolPolicy> m, ToolRisk risk, String... names) {
        for (String name : names) {
            m.put(name, ToolPolicy.of(risk));
        }
    }

    private static void addLimited(Map<String, ToolPolicy> m, ToolRisk risk, int maxInputChars, String... names) {
        for (String name : names) {
            m.put(name, ToolPolicy.limited(risk, maxInputChars));
        }
    }

    private static void addConfirm(Map<String, ToolPolicy> m, ToolRisk risk, String... names) {
        for (String name : names) {
            m.put(name, ToolPolicy.confirm(risk));
        }
    }
}
