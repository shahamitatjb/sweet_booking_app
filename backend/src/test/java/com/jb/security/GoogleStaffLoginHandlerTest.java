package com.jb.security;

import com.jb.domain.Staff;
import com.jb.repository.StaffRepository;
import com.jb.service.AuditService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GoogleStaffLoginHandlerTest {
    private final StaffRepository repo = mock(StaffRepository.class);
    private final GoogleStaffLoginHandler handler =
            new GoogleStaffLoginHandler(repo, mock(AuditService.class), mock(JwtService.class));

    @Test
    void googleNameOverwritesStoredNameOnLogin() {
        Staff s = Staff.builder().id(1L).email("a@b.co").name("Typed By Admin").role(Staff.Role.COUNTER).active(true).build();
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        handler.syncProfileName(s, Map.of("name", "Asha Patel"));

        assertThat(s.getName()).isEqualTo("Asha Patel");
        verify(repo).save(s);
    }

    @Test
    void fallsBackToGivenAndFamilyName() {
        Staff s = Staff.builder().id(1L).email("a@b.co").role(Staff.Role.COUNTER).active(true).build();
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        handler.syncProfileName(s, Map.of("given_name", "Asha", "family_name", "Patel"));

        assertThat(s.getName()).isEqualTo("Asha Patel");
    }

    @Test
    void noSaveWhenGoogleSendsNoNameOrSameName() {
        Staff s = Staff.builder().id(1L).email("a@b.co").name("Asha Patel").role(Staff.Role.COUNTER).active(true).build();

        handler.syncProfileName(s, new HashMap<>());
        handler.syncProfileName(s, Map.of("name", "Asha Patel"));

        assertThat(s.getName()).isEqualTo("Asha Patel");
        verify(repo, never()).save(any());
    }
}
