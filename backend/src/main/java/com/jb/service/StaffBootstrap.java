package com.jb.service;

import com.jb.domain.Staff;
import com.jb.repository.StaffRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Adds staff accounts listed in env vars on every startup: BOOTSTRAP_SUPER_ADMIN_EMAILS as
 * SUPER_ADMIN, BOOTSTRAP_ADMIN_EMAILS as ADMIN. Only emails not yet in the staff table are
 * inserted; existing rows are never changed, so later edits made in Settings stick.
 */
@Component
@Slf4j
public class StaffBootstrap implements ApplicationRunner {
    private final StaffRepository staffRepository;
    private final String superAdminEmails;
    private final String adminEmails;

    public StaffBootstrap(StaffRepository staffRepository,
                          @Value("${jb.bootstrap-super-admin-emails:}") String superAdminEmails,
                          @Value("${jb.bootstrap-admin-emails:}") String adminEmails) {
        this.staffRepository = staffRepository;
        this.superAdminEmails = superAdminEmails == null ? "" : superAdminEmails;
        this.adminEmails = adminEmails == null ? "" : adminEmails;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Super admins first, so an email listed in both lists gets the higher role.
        insertMissing(superAdminEmails, Staff.Role.SUPER_ADMIN, "BOOTSTRAP_SUPER_ADMIN_EMAILS");
        insertMissing(adminEmails, Staff.Role.ADMIN, "BOOTSTRAP_ADMIN_EMAILS");
    }

    private void insertMissing(String csv, Staff.Role role, String source) {
        List<String> missing = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(e -> !e.isEmpty())
                .map(String::toLowerCase)
                .distinct()
                .filter(e -> staffRepository.findByEmailIgnoreCase(e).isEmpty())
                .toList();
        if (missing.isEmpty()) return;
        List<Staff> added = missing.stream()
                .map(e -> Staff.builder().email(e).role(role).active(true).build())
                .toList();
        staffRepository.saveAll(added);
        log.warn("[AUTH] bootstrapped {} {} account(s) from {}: {}", added.size(), role, source, missing);
    }
}
