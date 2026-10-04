package com.jb.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecretsGuardTest {
    private static final String REAL = "k3J9xQ2vP8mN5bL7cR4tY6wZ1aS0dF3gH";

    @Test
    void realSecretsPass() {
        assertThat(ProductionSecretsGuard.problems(REAL, REAL + "q", "rzp_live_x", "s", "whsec")).isEmpty();
        assertThat(ProductionSecretsGuard.problems(REAL, REAL, null, null, null)).isEmpty();
    }

    @Test
    void missingDefaultOrShortSecretsAreReported() {
        assertThat(ProductionSecretsGuard.problems(null, "dev-only-change-me-32bytes-min!!", null, null, null))
                .containsExactly("JWT_SECRET is not set", "QR_HMAC_SECRET is the built-in development value");
        assertThat(ProductionSecretsGuard.problems("short", REAL, null, null, null))
                .containsExactly("JWT_SECRET is shorter than 32 bytes");
    }

    @Test
    void razorpayKeysNeedTheirSecretAndTheWebhookSecret() {
        assertThat(ProductionSecretsGuard.problems(REAL, REAL, "rzp_live_x", "", " "))
                .containsExactly("RAZORPAY_KEY_SECRET is not set",
                        "RAZORPAY_WEBHOOK_SECRET is not set (refunds and disputes would not cancel bookings)");
    }

    @Test
    void productionStartupFailsButDevelopmentProfilesAreExempt() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        prod.setProperty("jb.jwt-secret", "dev-jwt-secret-change-me-32bytes-minimum!");
        prod.setProperty("jb.qr-hmac-secret", REAL);
        assertThatThrownBy(() -> new ProductionSecretsGuard(prod).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");

        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThatCode(() -> new ProductionSecretsGuard(local).afterPropertiesSet()).doesNotThrowAnyException();

        MockEnvironment noProfile = new MockEnvironment();
        assertThatThrownBy(() -> new ProductionSecretsGuard(noProfile).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }
}
