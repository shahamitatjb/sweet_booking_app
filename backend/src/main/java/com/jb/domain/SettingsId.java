package com.jb.domain;

import java.io.Serializable;
import java.util.Objects;

public class SettingsId implements Serializable {
    private String key;
    private String language;

    public SettingsId() {}
    public SettingsId(String key, String language) {
        this.key = key;
        this.language = language;
    }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SettingsId that)) return false;
        return Objects.equals(key, that.key) && Objects.equals(language, that.language);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, language);
    }
}
