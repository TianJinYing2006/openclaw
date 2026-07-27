package com.example.ykdsummer.admin.web;

import com.example.ykdsummer.admin.config.AdminWebProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** Keeps the management UI off the existing 8080 bot/debug endpoint. */
public class AdminPortIsolationFilter extends OncePerRequestFilter {
    private final AdminWebProperties properties;

    public AdminPortIsolationFilter(AdminWebProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        boolean adminPort = request.getLocalPort() == properties.getPort();
        boolean adminPath = path.equals("/admin") || path.startsWith("/admin/");
        if (adminPath && !adminPort) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (adminPort && path.startsWith("/api/")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (adminPort && (path.isBlank() || path.equals("/"))) {
            response.sendRedirect("/admin");
            return;
        }
        chain.doFilter(request, response);
    }
}
