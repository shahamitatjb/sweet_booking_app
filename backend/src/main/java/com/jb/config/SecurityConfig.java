package com.jb.config;

import com.jb.security.CsrfCookieGuardFilter;
import com.jb.security.GoogleStaffLoginHandler;
import com.jb.security.StaffJwtFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final StaffJwtFilter staffJwtFilter;
    private final GoogleStaffLoginHandler googleStaffLoginHandler;

    @Value("${jb.frontend-origin}")
    private String frontendOrigin;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository clients) throws Exception {
        // Cookie-based double-submit CSRF: the API is stateless, so there is no HTTP
        // session to stash the token in. The SPA reads the XSRF-TOKEN cookie and echoes
        // it back in X-XSRF-TOKEN. The plain request handler is used (not the XOR one)
        // because the client can only send the raw cookie value, never a masked token.
        http.csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/api/webhooks/**", "/api/public/**"));
        // Swallow the token-clearing Set-Cookie that CookieCsrfTokenRepository emits on
        // every request; StaffJwtFilter re-asserts a usable token instead.
        http.addFilterBefore(new CsrfCookieGuardFilter(), CsrfFilter.class);
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(auth -> auth
                // Spring Boot forwards sendError() (403, 404, 500…) to /error. It must stay
                // reachable, or the original status is replaced by a login redirect.
                .requestMatchers("/error").permitAll()
                .requestMatchers("/api/health", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/public/**").permitAll()
                // Full customer details for a scanned booking: committee staff only.
                .requestMatchers(HttpMethod.GET, "/api/verify/*/staff").hasAnyRole("ADMIN", "COUNTER")
                .requestMatchers(HttpMethod.GET, "/api/verify/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
                .requestMatchers("/api/staff/**").hasAnyRole("ADMIN", "COUNTER")
                // The counter screen needs the catalogue, but it is a COUNTER-level job,
                // not an admin one. Read-only; writes below stay ADMIN-only.
                .requestMatchers(HttpMethod.GET, "/api/admin/items").hasAnyRole("ADMIN", "COUNTER")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
        );
        OAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
        http.oauth2Login(o -> o
                .authorizationEndpoint(a -> a.authorizationRequestResolver(resolver))
                .successHandler(googleStaffLoginHandler)
                // Absolute failure URL: callback runs on the API origin, and the API
                // has no /staff/login route of its own.
                .failureHandler((req, res, ex) -> {
                    log.error("[AUTH] Google OAuth2 login failed: uri={} remoteIp={} cause={}",
                            req.getRequestURI(), req.getRemoteAddr(), ex.toString(), ex);
                    res.sendRedirect(frontendOrigin + "/staff/login?error=oauth");
                })
                .loginPage(frontendOrigin + "/staff/login")
        );
        // API callers are fetch(), not page navigations: answer 401 instead of redirecting to the
        // Google sign-in page, whose HTML would otherwise read as a successful empty response.
        http.exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), new AntPathRequestMatcher("/api/**")));
        http.addFilterBefore(staffJwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CookieCsrfTokenRepository csrfRepository() {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setSecure(frontendOrigin.startsWith("https"));
        repo.setCookiePath("/");
        return repo;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        String[] origins = frontendOrigin.split(",");
        cfg.setAllowedOrigins(List.of(origins));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
