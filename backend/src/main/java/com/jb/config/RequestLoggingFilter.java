package com.jb.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * One INFO line per HTTP request: method, path, status, duration.
 * Registered ahead of the security chain so blocked/redirected requests are
 * visible too. Query strings are omitted on purpose — they can carry OTP codes
 * and QR signatures.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            String path = request.getRequestURI();
            if (!isNoisy(path)) {
                if (status >= 500) {
                    logger.error("[HTTP] {} {} -> {} ({} ms)", request.getMethod(), path, status, ms);
                } else if (status >= 400) {
                    logger.warn("[HTTP] {} {} -> {} ({} ms)", request.getMethod(), path, status, ms);
                } else {
                    logger.info("[HTTP] {} {} -> {} ({} ms)", request.getMethod(), path, status, ms);
                }
            }
        }
    }

    private boolean isNoisy(String path) {
        return path.startsWith("/actuator") || path.equals("/api/health") || path.startsWith("/favicon");
    }

    @Component
    public static class Registration {
        public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter() {
            FilterRegistrationBean<RequestLoggingFilter> bean =
                    new FilterRegistrationBean<>(new RequestLoggingFilter());
            bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            bean.addUrlPatterns("/*");
            return bean;
        }
    }
}
