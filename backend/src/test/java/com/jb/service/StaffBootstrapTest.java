package com.jb.service;

import com.jb.domain.Staff;
import com.jb.repository.StaffRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class StaffBootstrapTest {
    private final StaffRepository repo = mock(StaffRepository.class);

    @Test
    void insertsListedEmailsThatAreMissingWithTheirRole() {
        when(repo.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        new StaffBootstrap(repo, " Boss@Example.com ", " Amit@Example.com, shailesh@example.com ,,").run(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Staff>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo, times(2)).saveAll(captor.capture());
        List<Staff> supers = captor.getAllValues().get(0);
        List<Staff> admins = captor.getAllValues().get(1);
        assertThat(supers).extracting(Staff::getEmail).containsExactly("boss@example.com");
        assertThat(supers).allMatch(s -> s.getRole() == Staff.Role.SUPER_ADMIN && s.isActive());
        assertThat(admins).extracting(Staff::getEmail).containsExactly("amit@example.com", "shailesh@example.com");
        assertThat(admins).allMatch(s -> s.getRole() == Staff.Role.ADMIN && s.isActive());
    }

    @Test
    void leavesExistingStaffUntouched() {
        when(repo.findByEmailIgnoreCase("x@y.z")).thenReturn(Optional.of(
                Staff.builder().email("x@y.z").role(Staff.Role.COUNTER).active(false).build()));
        new StaffBootstrap(repo, "x@y.z", "X@Y.z").run(null);
        verify(repo, never()).saveAll(anyList());
    }

    @Test
    void doesNothingWhenListsAreBlank() {
        new StaffBootstrap(repo, "", "  ").run(null);
        verify(repo, never()).saveAll(anyList());
    }
}
