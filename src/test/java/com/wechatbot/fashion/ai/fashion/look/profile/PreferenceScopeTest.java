package com.wechatbot.fashion.ai.fashion.look.profile;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 记忆治理：偏好作用域判定。 */
class PreferenceScopeTest {

    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

    @Test
    void oneOffExpressionIsSessionScopedWithExpiry() {
        PreferenceScope scope = PreferenceScope.classify("这次不要红色，太正式了", NOW);
        assertThat(scope.scope()).isEqualTo(PreferenceScope.SESSION);
        assertThat(scope.expiresAt()).isEqualTo(NOW.plus(PreferenceScope.SESSION_TTL));
    }

    @Test
    void explicitLongTermPreferenceHasNoExpiry() {
        PreferenceScope scope = PreferenceScope.classify("我平时不喜欢穿高跟鞋", NOW);
        assertThat(scope.scope()).isEqualTo(PreferenceScope.LONG_TERM);
        assertThat(scope.expiresAt()).isNull();
    }

    @Test
    void blankOrNullDefaultsToLongTerm() {
        assertThat(PreferenceScope.classify(null, NOW).scope()).isEqualTo(PreferenceScope.LONG_TERM);
        assertThat(PreferenceScope.classify("  ", NOW).scope()).isEqualTo(PreferenceScope.LONG_TERM);
    }
}
