package com.jb.repository;

import com.jb.domain.Settings;
import com.jb.domain.SettingsId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettingsRepository extends JpaRepository<Settings, SettingsId> {
    Optional<Settings> findByKeyAndLanguage(String key, String language);
    List<Settings> findByKey(String key);
}
