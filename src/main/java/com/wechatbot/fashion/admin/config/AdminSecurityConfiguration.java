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

/** A single local administrator account. The password is intentionally read only from ignored local config or env. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminSecurityConfiguration {

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
        if (password == null || password.isBlank() || password.equals("replace-with-a-local-strong-password")) {
            throw new IllegalStateException("app.admin.password is required when app.admin.enabled=true");
        }
        String encoded = password.startsWith("$2a$") || password.startsWith("$2b$") || password.startsWith("$2y$")
                ? password
                : encoder.encode(password);
        return new InMemoryUserDetailsManager(User.withUsername(properties.getUsername())
                .password(encoded)
                .roles("ADMIN")
                .build());
    }

    @Bean
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, AdminWebProperties properties) throws Exception {
        // 暂时关闭管理后台登录鉴权：/admin/** 全部放行、CSRF 也对 /admin 放行，页面可直接访问无需登录。
        // 登录页 /admin/login 与登出 /admin/logout 仍保留可用，但不强制；后续重新设计鉴权时把下方 permitAll 改回 hasRole("ADMIN") 即可。
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/admin/**"));
        http.addFilterBefore(new AdminPortIsolationFilter(properties), UsernamePasswordAuthenticationFilter.class);
        if (!properties.isEnabled()) {
            return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
        }
        return http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/admin/**").permitAll()
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
