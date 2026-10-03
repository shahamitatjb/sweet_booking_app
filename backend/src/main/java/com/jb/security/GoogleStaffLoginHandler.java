package com.jb.security;

import com.jb.domain.Staff;
import com.jb.repository.StaffRepository;
import com.jb.service.AuditService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleStaffLoginHandler implements AuthenticationSuccessHandler {
    private final StaffRepository staffRepository;
    private final AuditService auditService;
    private final JwtService jwtService;

    @Value("${jb.frontend-origin}")
    private String frontendOrigin;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                          Authentication authentication) throws java.io.IOException {
        String email = null;
        try {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
            OAuth2User user = token.getPrincipal();
            email = String.valueOf(user.getAttributes().getOrDefault("email", ""));
            log.info("[AUTH] Google OIDC success: client={} email={} remoteIp={}",
                    token.getAuthorizedClientRegistrationId(), email, request.getRemoteAddr());

            Optional<Staff> staff = staffRepository.findByEmailIgnoreCaseAndActiveTrue(email);
            if (staff.isEmpty()) {
                log.warn("[AUTH] SIGN-IN DENIED: {} is not an active row in the staff table (allowlist). "
                        + "Add it via: INSERT INTO staff (email, name, role, active) VALUES ('{}', NULL, 'COUNTER', TRUE);", email, email);
                auditService.recordOutsideTx("staff_signin_denied", email, null, null,
                        Map.of("reason", "not_on_allowlist"), request.getRemoteAddr(), null);
                redirect(response, "/staff/login?error=denied");
                return;
            }
            Staff s = staff.get();
            auditService.recordOutsideTx("staff_signin", s.getEmail(), s.getId(), s.getRole().name(),
                    Map.of(), request.getRemoteAddr(), null);
            String jwt = jwtService.issue(s);
            Cookie c = new Cookie("jb_token", jwt);
            c.setHttpOnly(true);
            // Secure only over https origins (Frontend origin drives browser-facing scheme).
            c.setSecure(frontendOrigin != null && frontendOrigin.startsWith("https"));
            c.setPath("/");
            c.setMaxAge(60 * 60 * 8);
            response.addCookie(c);
            String landing = s.getRole() == Staff.Role.ADMIN ? "/admin" : "/staff/counter";
            log.info("[AUTH] SIGN-IN OK: staffId={} role={} email={} -> {} (jb_token, 8h)",
                    s.getId(), s.getRole(), s.getEmail(), landing);
            // Absolute redirect: the OAuth callback is served by the API on :8080,
            // so a relative /admin would land on the API origin and 404.
            redirect(response, landing);
        } catch (Exception e) {
            log.error("[AUTH] SIGN-IN FAILED: unexpected error during Google callback (email={})", email, e);
            redirect(response, "/staff/login?error=failed");
        }
    }

    private void redirect(HttpServletResponse response, String path) throws java.io.IOException {
        String target = path.startsWith("http") ? path : frontendOrigin + path;
        try {
            response.sendRedirect(target);
        } catch (java.io.IOException e) {
            log.error("[AUTH] Redirect after login failed -> {}", target, e);
            throw e;
        }
    }
}
