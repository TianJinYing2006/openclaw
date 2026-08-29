package com.wechatbot.fashion.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /api/** 调试接口的本机访问限制。
 *
 * <p>项目默认把 {@code POST /api/ilink/send} 等主动接口暴露在 8080 端口且无鉴权，
 * 开发期局域网内其它机器可直接调用。此过滤器在开启 {@code app.api-security.localhost-only}
 * 时拒绝非本机来源的 /api/** 请求（返回 403），本机联调（PowerShell/Postman）不受影响。</p>
 *
 * <p>关闭方式：{@code app.api-security.localhost-only=false}（多机联调场景）。</p>
 */
@Component
public class ApiSecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiSecurityFilter.class);

    private final ApiSecurityProperties properties;

    public ApiSecurityFilter(ApiSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!properties.isLocalhostOnly() || !isApiPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String remoteAddr = request.getRemoteAddr();
        if (isLoopback(remoteAddr)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Rejected non-localhost /api request: method={}, path={}, remote={}",
                request.getMethod(), request.getRequestURI(), remoteAddr);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"/api interfaces are localhost-only in dev\"}");
    }

    private static boolean isApiPath(String path) {
        return path != null && path.startsWith("/api/");
    }

    private static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
