package com.jb.service;

import com.jb.domain.Settings;
import com.jb.domain.SettingsId;
import com.jb.repository.SettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SettingsService {
    private final SettingsRepository settingsRepository;

    public String get(String key, String language) {
        return getOptional(key, language).map(Settings::getValue).orElse(null);
    }

    public String getOrDefault(String key, String language, String defaultValue) {
        return Optional.ofNullable(get(key, language)).filter(s -> !s.isBlank()).orElse(defaultValue);
    }

    public Optional<Settings> getOptional(String key, String language) {
        return settingsRepository.findByKeyAndLanguage(key, language);
    }

    public Map<String, String> getForLanguage(String language) {
        Map<String, String> map = new HashMap<>();
        List<Settings> all = settingsRepository.findAll();
        for (Settings s : all) {
            if (s.getLanguage().equals(language)) {
                map.put(s.getKey(), s.getValue());
            } else {
                map.putIfAbsent(s.getKey(), s.getValue());
            }
        }
        return map;
    }

    @Transactional
    public void put(String key, String language, String value) {
        Settings s = settingsRepository.findByKeyAndLanguage(key, language)
                .orElseGet(() -> Settings.builder()
                        .key(key)
                        .language(language)
                        .build());
        s.setValue(value);
        settingsRepository.save(s);
    }

    public boolean bookingEnabled() {
        String v = getOrDefault("booking_enabled", "en", "true");
        return Boolean.parseBoolean(v);
    }

    public Instant windowOpen() {
        return parseInstant(getOrDefault("booking_window_open", "en", ""));
    }

    public Instant windowClose() {
        return parseInstant(getOrDefault("booking_window_close", "en", ""));
    }

    public boolean withinWindow(Instant now) {
        if (!bookingEnabled()) return false;
        Instant open = windowOpen();
        Instant close = windowClose();
        if (open != null && now.isBefore(open)) return false;
        if (close != null && now.isAfter(close)) return false;
        return true;
    }

    public boolean isPinAllowed(String pin) {
        String list = getOrDefault("allowed_pins", "en", "411001-411062");
        for (String part : list.split(",")) {
            part = part.trim();
            if (part.contains("-")) {
                String[] b = part.split("-");
                if (b.length == 2) {
                    int p = Integer.parseInt(pin);
                    int from = Integer.parseInt(b[0].trim());
                    int to = Integer.parseInt(b[1].trim());
                    if (p >= from && p <= to) return true;
                }
            } else if (part.equals(pin)) {
                return true;
            }
        }
        return false;
    }

    public int maxPacketsPerItem() {
        return Integer.parseInt(getOrDefault("max_packets_per_item", "en", "20"));
    }

    public int maxPacketsTotal() {
        return Integer.parseInt(getOrDefault("max_packets_total", "en", "50"));
    }

    public Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(raw).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
