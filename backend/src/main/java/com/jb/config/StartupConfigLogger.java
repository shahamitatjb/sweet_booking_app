package com.jb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Prints the effective integration configuration at startup so a misconfigured
 * deployment (e.g. GOOGLE_CLIENT_ID never reaching the JVM) is visible in the
 * logs instead of surfacing later as an opaque provider error.
 */
@Component
public class StartupConfigLogger {
    private static final Logger log = LoggerFactory.getLogger(StartupConfigLogger.class);

    private final Environment env;

    public StartupConfigLogger(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logConfig() {
        String clientId = env.getProperty("spring.security.oauth2.client.registration.google.client-id", "placeholder");
        String clientSecret = env.getProperty("spring.security.oauth2.client.registration.google.client-secret", "");
        String osClientId = System.getenv("GOOGLE_CLIENT_ID");
        String frontendOrigin = env.getProperty("jb.frontend-origin", "http://localhost:3000");
        String serverPort = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        String redirectUri = "http://localhost:" + serverPort + "/login/oauth2/code/google";

        boolean placeholder = clientId == null || clientId.isBlank() || "placeholder".equals(clientId);
        String source = osClientId != null && !osClientId.isBlank()
                ? "OS environment variable"
                : (env.containsProperty("GOOGLE_CLIENT_ID") ? ".env / config file" : "NOT SET (using application.yml default)");

        if (placeholder) {
            log.error("=================================================================");
            log.error(" Google OAuth is NOT configured — sign-in will fail with");
            log.error(" 'Error 401: invalid_client'.");
            log.error("   GOOGLE_CLIENT_ID source : {}", source);
            log.error("   effective client-id     : {}", clientId);
            log.error(" Fix: set GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET in .env at the");
            log.error(" repo root (or export them), then restart the API.");
            log.error("=================================================================");
        } else {
            log.info("Google OAuth client configured — client-id={} source={} secret={}",
                    mask(clientId), source, clientSecret == null || clientSecret.isBlank() ? "MISSING" : "set(" + clientSecret.length() + " chars)");
            log.info("Register this redirect URI in Google Console -> Credentials -> Web application:");
            log.info("   {}", redirectUri);
        }

        log.info("Config: FRONTEND_ORIGIN={} server.port={} database={} emailProvider={} otpProvider={} razorpayMode={}",
                frontendOrigin,
                serverPort,
                maskUrl(env.getProperty("spring.datasource.url", "n/a")),
                env.getProperty("jb.email-provider", "n/a"),
                env.getProperty("jb.otp-provider", "n/a"),
                env.getProperty("jb.razorpay-mode", "n/a"));
        log.info("Config: razorpayKeys={} resendKey={} jwtSecret={}",
                env.getProperty("jb.razorpay-key-id", "") != null && !env.getProperty("jb.razorpay-key-id", "").isBlank() ? "set" : "not set",
                env.getProperty("jb.resend-api-key", "") != null && !env.getProperty("jb.resend-api-key", "").isBlank() ? "set" : "not set",
                env.getProperty("jb.jwt-secret", "") != null && !env.getProperty("jb.jwt-secret", "").isBlank() ? "set" : "MISSING");
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return "<empty>";
        if (value.length() <= 24) return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
        return value.substring(0, 14) + "***" + value.substring(value.length() - 14);
    }

    private String maskUrl(String url) {
        if (url == null) return "n/a";
        return url.replaceAll("://([^:/@]+):([^@]+)@", "://$1:***@");
    }
}
