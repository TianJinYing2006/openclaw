package com.wechatbot.fashion.admin.config;

import com.wechatbot.fashion.admin.web.AdminPortIsolationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import java.util.Locale;
import java.util.Set;

/** A single local administrator account. The password is intentionally read only from ignored local config or env. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminSecurityConfiguration {

    /** 明确拒绝的占位/默认口令，避免「有鉴权但用默认密码」这种伪安全。 */
    private static final Set<String> REJECTED_DEFAULT_PASSWORDS = Set.of(
            "change-me", "change-me-strong", "changeme", "admin", "password", "123456",
            "replace-with-a-local-strong-password", "your-strong-password");

    private static final int MIN_PASSWORD_LENGTH = 8;

    @Bean
    public PasswordEncoder adminPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService localAdminUserDetailsService(AdminWebProperties properties, PasswordEncoder encoder) {
        if (!properties.isEnabled()) {
            return username -> { throw new UsernameNotFoundException("Administrator website is disabled"); };
        }
        String password = properties.getPassword();
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("app.admin.password is required when app.admin.enabled=true");
        }
        String normalized = password.strip();
        if (normalized.length() < MIN_PASSWORD_LENGTH
                || REJECTED_DEFAULT_PASSWORDS.contains(normalized.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "app.admin.password must not be a default/placeholder value and must be at least "
                            + MIN_PASSWORD_LENGTH + " characters when app.admin.enabled=true");
        }
        String encoded = normalized.startsWith("$2a$") || normalized.startsWith("$2b$") || normalized.startsWith("$2y$")
                ? normalized
                : encoder.encode(normalized);
        return new InMemoryUserDetailsManager(User.withUsername(properties.getUsername())
                .password(encoded)
                .roles("ADMIN")
                .build());
    }

    @Bean
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, AdminWebProperties properties) throws Exception {
        // /api/** 是 JSON 调试接口，靠本机限制 + 令牌保护，不做表单 CSRF；/admin/** 保留 CSRF 保护。
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
        http.addFilterBefore(new AdminPortIsolationFilter(properties), UsernamePasswordAuthenticationFilter.class);
        if (!properties.isEnabled()) {
            return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
        }
        return http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/admin/login", "/admin/assets/**", "/admin/favicon.ico").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .formLogin(login -> login
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .defaultSuccessUrl("/admin", true)
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/admin/logout").logoutSuccessUrl("/admin"))
                .build();
    }
}
