package com.example.ykdsummer.admin.config;

import com.example.ykdsummer.admin.web.AdminPortIsolationFilter;
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
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
        http.addFilterBefore(new AdminPortIsolationFilter(properties), UsernamePasswordAuthenticationFilter.class);
        if (!properties.isEnabled()) {
            return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
        }
        return http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/admin/login", "/admin/assets/**").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .formLogin(login -> login
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .defaultSuccessUrl("/admin", true)
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/admin/logout").logoutSuccessUrl("/admin/login?logout"))
                .build();
    }
}
