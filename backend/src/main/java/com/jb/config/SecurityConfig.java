package com.jb.config;

import com.jb.security.GoogleStaffLoginHandler;
import com.jb.security.StaffJwtFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/webhooks/**", "/api/public/**"));
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/public/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/verify/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
                .requestMatchers("/api/staff/**").hasAnyRole("ADMIN", "COUNTER")
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
        http.addFilterBefore(staffJwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
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
