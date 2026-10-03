package com.jb.service;

import com.jb.domain.Settings;
import com.jb.repository.SettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettingsServiceOtpTest {
    private final SettingsRepository repo = mock(SettingsRepository.class);
    private final SettingsService service = new SettingsService(repo);

    @Test
    void otpRequiredDefaultsToFalseWhenSettingMissing() {
        when(repo.findByKeyAndLanguage(eq("otp_required"), eq("en"))).thenReturn(Optional.empty());
        assertThat(service.otpRequired()).isFalse();
    }

    @Test
    void otpRequiredTrueWhenSettingIsTrue() {
        when(repo.findByKeyAndLanguage(eq("otp_required"), eq("en")))
                .thenReturn(Optional.of(Settings.builder().key("otp_required").language("en").value("true").build()));
        assertThat(service.otpRequired()).isTrue();
    }

    @Test
    void otpChannelFollowsProviderSetting() {
        when(repo.findByKeyAndLanguage(eq("otp_provider"), eq("en")))
                .thenReturn(Optional.of(Settings.builder().key("otp_provider").language("en").value("sms").build()));
        assertThat(service.otpChannel()).isEqualTo(OtpService.Channel.sms);

        when(repo.findByKeyAndLanguage(eq("otp_provider"), eq("en"))).thenReturn(Optional.empty());
        assertThat(service.otpChannel()).isEqualTo(OtpService.Channel.email);
    }
}
