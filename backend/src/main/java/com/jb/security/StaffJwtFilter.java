package com.jb.security;

import com.jb.domain.Staff;
import com.jb.repository.StaffRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class StaffJwtFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final StaffRepository staffRepository;

    @org.springframework.beans.factory.annotation.Value("${jb.frontend-origin}")
    private String frontendOrigin;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extract(request);
        if (token != null) {
            try {
                if (!jwtService.isValid(token)) {
                    log.info("[AUTH] JWT rejected (invalid/expired/tampered) for {} {} — continuing unauthenticated",
                            request.getMethod(), request.getRequestURI());
                } else {
                    Claims claims = jwtService.parse(token);
                    String email = claims.getSubject();
                    Optional<Staff> staff = staffRepository.findByEmailIgnoreCaseAndActiveTrue(email);
                    if (staff.isPresent()) {
                        Staff s = staff.get();
                        var auth = new UsernamePasswordAuthenticationToken(
                                s.getEmail(),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + s.getRole().name())));
                        auth.setDetails(s);
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        log.debug("[AUTH] JWT ok: {} role={} on {} {}", email, s.getRole(),
                                request.getMethod(), request.getRequestURI());
                    } else {
                        log.info("[AUTH] JWT valid for '{}' but no active staff row — continuing unauthenticated on {} {}",
                                email, request.getMethod(), request.getRequestURI());
                    }
                }
            } catch (Exception e) {
                log.warn("[AUTH] JWT processing error on {} {}: {} — continuing unauthenticated",
                        request.getMethod(), request.getRequestURI(), e.toString());
                SecurityContextHolder.clearContext();
            }
        }
        ensureCsrfCookie(request, response);
        chain.doFilter(request, response);
    }

    /**
     * CsrfFilter only materialises the CSRF token for unsafe methods, and it clears the
     * XSRF-TOKEN cookie on every request that carries one. That makes the cookie
     * single-use, which breaks a SPA that wants to POST twice in a row (export then void,
     * save then save). So every response re-asserts a cookie: the value already in the
     * request if there is one, otherwise a freshly materialised token. Written before the
     * chain, this Set-Cookie lands after Spring's own and wins in the browser.
     */
    private void ensureCsrfCookie(HttpServletRequest request, HttpServletResponse response) {
        String value = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("XSRF-TOKEN".equals(c.getName()) && c.getValue() != null && !c.getValue().isEmpty()) {
                    value = c.getValue();
                }
            }
        }
        if (value == null) {
            Object attr = request.getAttribute(CsrfToken.class.getName());
            if (attr instanceof CsrfToken csrfToken) {
                value = csrfToken.getToken();
            } else if (attr instanceof org.springframework.security.web.csrf.DeferredCsrfToken deferred) {
                CsrfToken token = deferred.get();
                value = token == null ? null : token.getToken();
            }
        }
        if (value == null || value.isEmpty()) return;
        Cookie cookie = new Cookie("XSRF-TOKEN", value);
        cookie.setPath("/");
        cookie.setHttpOnly(false);
        cookie.setSecure(frontendOrigin.startsWith("https"));
        response.addCookie(cookie);
        log.debug("[AUTH] set XSRF-TOKEN cookie for {} {}", request.getMethod(), request.getRequestURI());
    }

    private String extract(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("jb_token".equals(c.getName())) return c.getValue();
            }
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        return null;
    }
}
