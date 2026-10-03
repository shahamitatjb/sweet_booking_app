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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class StaffJwtFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final StaffRepository staffRepository;

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
        chain.doFilter(request, response);
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
