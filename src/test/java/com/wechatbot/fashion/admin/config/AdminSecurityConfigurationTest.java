package com.wechatbot.fashion.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理后台鉴权守门测试：禁止默认/占位/过短口令，避免「有鉴权但用 change-me」的伪安全。
 */
class AdminSecurityConfigurationTest {

    private final AdminSecurityConfiguration configuration = new AdminSecurityConfiguration();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private static AdminWebProperties enabledWithPassword(String password) {
        AdminWebProperties properties = new AdminWebProperties();
        properties.setEnabled(true);
        properties.setUsername("admin");
        properties.setPassword(password);
        return properties;
    }

    @Test
    void rejectsDefaultPlaceholderPasswords() {
        for (String weak : new String[]{"change-me", "change-me-strong", "admin", "password", "123456",
                "replace-with-a-local-strong-password"}) {
            assertThrows(IllegalStateException.class,
                    () -> configuration.localAdminUserDetailsService(enabledWithPassword(weak), encoder),
                    "默认/占位口令必须被拒绝: " + weak);
        }
    }

    @Test
    void rejectsTooShortPasswords() {
        assertThrows(IllegalStateException.class,
                () -> configuration.localAdminUserDetailsService(enabledWithPassword("short"), encoder));
    }

    @Test
    void rejectsBlankPassword() {
        assertThrows(IllegalStateException.class,
                () -> configuration.localAdminUserDetailsService(enabledWithPassword("   "), encoder));
    }

    @Test
    void acceptsStrongPasswordAndGrantsAdminRole() {
        UserDetailsService service = configuration.localAdminUserDetailsService(
                enabledWithPassword("Str0ng-Passw0rd!"), encoder);

        UserDetails admin = service.loadUserByUsername("admin");

        assertTrue(admin.getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")),
                "管理员应具备 ROLE_ADMIN");
    }
}
