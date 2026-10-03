package com.jb.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OtpServiceTest {
    private EmailService emailService;
    private MutableClock clock;
    private OtpService service;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-03T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        clock = new MutableClock();
        service = new OtpService(emailService, "dev", 5, clock);
    }

    @Test
    void issuedCodeVerifiesOnceThenIsConsumed() {
        var issued = service.issue("9876543210", OtpService.Channel.sms);

        assertThat(issued.devCode()).hasSize(6);
        assertThat(service.verify("9876543210", OtpService.Channel.sms, issued.devCode())).isTrue();
        assertThat(service.verify("9876543210", OtpService.Channel.sms, issued.devCode())).isFalse();
    }

    @Test
    void wrongCodeFailsAndCorrectCodeStillWorksWithinAttemptLimit() {
        var issued = service.issue("9876543210", OtpService.Channel.sms);

        assertThat(service.verify("9876543210", OtpService.Channel.sms, "000000")).isFalse();
        assertThat(service.verify("9876543210", OtpService.Channel.sms, issued.devCode())).isTrue();
    }

    @Test
    void fiveWrongAttemptsInvalidateTheCode() {
        var issued = service.issue("9876543210", OtpService.Channel.sms);

        for (int i = 0; i < 5; i++) {
            assertThat(service.verify("9876543210", OtpService.Channel.sms, "111111")).isFalse();
        }
        assertThat(service.verify("9876543210", OtpService.Channel.sms, issued.devCode())).isFalse();
    }

    @Test
    void expiredCodeFails() {
        var issued = service.issue("9876543210", OtpService.Channel.sms);
        clock.advance(Duration.ofMinutes(6));

        assertThat(service.verify("9876543210", OtpService.Channel.sms, issued.devCode())).isFalse();
    }

    @Test
    void verifyWithoutAnyIssuedCodeFails() {
        assertThat(service.verify("9876543210", OtpService.Channel.sms, "123456")).isFalse();
    }

    @Test
    void emailChannelRequiresEmailDestination() {
        assertThatThrownBy(() -> service.issue("9876543210", OtpService.Channel.email))
                .isInstanceOf(IllegalArgumentException.class);
        var issued = service.issue("a@b.co", OtpService.Channel.email);
        assertThat(service.verify("a@b.co", OtpService.Channel.email, issued.devCode())).isTrue();
    }

    @Test
    void destinationIsNormalisedForMobile() {
        var issued = service.issue("+919876543210", OtpService.Channel.sms);
        assertThat(service.verify("9876543210", OtpService.Channel.sms, issued.devCode())).isTrue();
    }

    @Test
    void sixthRequestInWindowIsRateLimited() {
        for (int i = 0; i < 5; i++) service.issue("9876543210", OtpService.Channel.sms);
        assertThatThrownBy(() -> service.issue("9876543210", OtpService.Channel.sms))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Too many");
    }
}
