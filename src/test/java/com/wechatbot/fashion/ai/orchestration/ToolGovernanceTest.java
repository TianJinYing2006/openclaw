package com.wechatbot.fashion.ai.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具治理策略守门测试：风险分级、默认只读、输入上限、需确认集合。
 */
class ToolGovernanceTest {

    @Test
    void classifiesRepresentativeTools() {
        assertThat(ToolGovernance.riskOf("get_current_weather")).isEqualTo(ToolRisk.READ_ONLY);
        assertThat(ToolGovernance.riskOf("get_current_china_time")).isEqualTo(ToolRisk.READ_ONLY);
        assertThat(ToolGovernance.riskOf("search_web")).isEqualTo(ToolRisk.EXTERNAL_DATA);
        assertThat(ToolGovernance.riskOf("inspect_image")).isEqualTo(ToolRisk.EXTERNAL_DATA);
        assertThat(ToolGovernance.riskOf("analyze_wardrobe_photo")).isEqualTo(ToolRisk.USER_DATA);
        assertThat(ToolGovernance.riskOf("virtual_try_on_wardrobe_item")).isEqualTo(ToolRisk.PAID_OPERATION);
        assertThat(ToolGovernance.riskOf("generate_image")).isEqualTo(ToolRisk.PAID_OPERATION);
        assertThat(ToolGovernance.riskOf("add_wardrobe_item")).isEqualTo(ToolRisk.SIDE_EFFECT);
    }

    @Test
    void undeclaredToolDefaultsToReadOnly() {
        assertThat(ToolGovernance.riskOf("some_future_tool")).isEqualTo(ToolRisk.READ_ONLY);
        assertThat(ToolGovernance.riskOf(null)).isEqualTo(ToolRisk.READ_ONLY);
    }

    @Test
    void highRiskSetCoversPrivacyCostAndSideEffects() {
        assertThat(ToolGovernance.highRiskTools())
                .contains("analyze_wardrobe_photo", "virtual_try_on_wardrobe_item", "purge_wardrobe_item")
                .doesNotContain("get_current_weather", "search_wardrobe");
    }

    @Test
    void destructiveToolsRequireConfirmation() {
        assertThat(ToolGovernance.confirmationRequiredTools())
                .contains("purge_wardrobe_item", "delete_wardrobe_item", "cancel_wechat_reminder");
    }

    @Test
    void appliesRiskBasedTimeoutAndCallBudgetDefaults() {
        assertThat(ToolGovernance.policyOf("get_current_weather").timeoutMillis()).isEqualTo(20_000L);
        assertThat(ToolGovernance.policyOf("generate_image").timeoutMillis()).isEqualTo(200_000L);
        assertThat(ToolGovernance.policyOf("generate_image").maxCallsPerRun()).isEqualTo(5);
        assertThat(ToolGovernance.policyOf("search_web").maxCallsPerRun()).isEqualTo(10);
    }

    @Test
    void boundedToolsDeclareInputLimit() {
        assertThat(ToolGovernance.policyOf("get_current_weather").maxInputChars()).isEqualTo(500);
        assertThat(ToolGovernance.policyOf("get_current_china_time").maxInputChars()).isEqualTo(100);
        assertThat(ToolGovernance.policyOf("search_web").maxInputChars()).isEqualTo(2000);
        // 未声明上限的工具为 0（不限制）
        assertThat(ToolGovernance.policyOf("generate_image").maxInputChars()).isZero();
    }
}
