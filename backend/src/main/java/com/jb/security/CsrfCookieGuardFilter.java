package com.jb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * CookieCsrfTokenRepository clears the XSRF-TOKEN cookie on every request that carries
 * one (saveToken(null) → Max-Age=0). The SPA needs a token it can echo back on the next
 * POST, so the deletion is swallowed: tokens are re-asserted by StaffJwtFilter instead,
 * and consecutive state-changing requests keep working without an intervening GET.
 */
public class CsrfCookieGuardFilter extends OncePerRequestFilter {
    private static final String NAME = "XSRF-TOKEN";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, new GuardedResponse(response));
    }

    private static boolean isRemoval(Cookie cookie) {
        if (cookie == null || !NAME.equals(cookie.getName())) return false;
        return cookie.getMaxAge() == 0 || cookie.getValue() == null || cookie.getValue().isEmpty();
    }

    private static boolean isRemovalHeader(String value) {
        if (value == null) return false;
        String v = value.toLowerCase(Locale.ROOT);
        if (!v.startsWith(NAME.toLowerCase(Locale.ROOT) + "=")) return false;
        return v.contains("max-age=0") || v.contains("expires=thu, 01 jan 1970");
    }

    private static final class GuardedResponse extends HttpServletResponseWrapper {
        GuardedResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void addCookie(Cookie cookie) {
            if (isRemoval(cookie)) return;
            super.addCookie(cookie);
        }

        @Override
        public void addHeader(String name, String value) {
            if ("Set-Cookie".equalsIgnoreCase(name) && isRemovalHeader(value)) return;
            super.addHeader(name, value);
        }
    }
}
