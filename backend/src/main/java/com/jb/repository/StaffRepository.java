package com.jb.repository;

import com.jb.domain.Staff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaffRepository extends JpaRepository<Staff, Long> {
    Optional<Staff> findByEmailIgnoreCaseAndActiveTrue(String email);
    Optional<Staff> findByEmailIgnoreCase(String email);
}
