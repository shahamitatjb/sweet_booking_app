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
 * Seeds the first admin accounts on a fresh database so someone can sign in after deploy.
 * Runs only when the staff table is empty; later changes go through the admin screen.
 */
@Component
@Slf4j
public class StaffBootstrap implements ApplicationRunner {
    private final StaffRepository staffRepository;
    private final String adminEmails;

    public StaffBootstrap(StaffRepository staffRepository,
                          @Value("${jb.bootstrap-admin-emails:}") String adminEmails) {
        this.staffRepository = staffRepository;
        this.adminEmails = adminEmails == null ? "" : adminEmails;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> emails = Arrays.stream(adminEmails.split(","))
                .map(String::trim)
                .filter(e -> !e.isEmpty())
                .map(String::toLowerCase)
                .distinct()
                .toList();
        if (emails.isEmpty()) return;
        if (staffRepository.count() > 0) {
            log.info("[AUTH] staff table already populated; BOOTSTRAP_ADMIN_EMAILS ignored");
            return;
        }
        List<Staff> admins = emails.stream()
                .map(e -> Staff.builder().email(e).role(Staff.Role.ADMIN).active(true).build())
                .toList();
        staffRepository.saveAll(admins);
        log.warn("[AUTH] bootstrapped {} admin account(s) from BOOTSTRAP_ADMIN_EMAILS: {}", admins.size(), emails);
    }
}
