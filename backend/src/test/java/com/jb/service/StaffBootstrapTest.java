package com.jb.service;

import com.jb.domain.Staff;
import com.jb.repository.StaffRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class StaffBootstrapTest {
    private final StaffRepository repo = mock(StaffRepository.class);

    @Test
    void insertsAdminsWhenTableIsEmpty() {
        when(repo.count()).thenReturn(0L);
        new StaffBootstrap(repo, " Amit@Example.com, shailesh@example.com ,,").run(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Staff>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Staff::getEmail).containsExactly("amit@example.com", "shailesh@example.com");
        assertThat(captor.getValue()).allMatch(s -> s.getRole() == Staff.Role.ADMIN && s.isActive());
    }

    @Test
    void doesNothingWhenStaffExistOrListIsBlank() {
        when(repo.count()).thenReturn(3L);
        new StaffBootstrap(repo, "x@y.z").run(null);
        when(repo.count()).thenReturn(0L);
        new StaffBootstrap(repo, "  ").run(null);
        verify(repo, never()).saveAll(anyList());
    }
}
