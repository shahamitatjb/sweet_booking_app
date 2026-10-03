package com.jb.service;

import com.jb.domain.Order;
import com.jb.domain.Staff;
import com.jb.repository.StaffRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookedByResolverTest {
    private final StaffRepository repo = mock(StaffRepository.class);
    private final BookedByResolver resolver = new BookedByResolver(repo);

    @Test
    void onlineBookingsReadOnline() {
        Order o = Order.builder().channel(Order.Channel.online).build();
        assertThat(resolver.bookedBy(o)).isEqualTo("Online");
    }

    @Test
    void counterBookingsUseStaffName() {
        when(repo.findById(5L)).thenReturn(Optional.of(Staff.builder().id(5L).email("a@b.co").name("Amit Shah").role(Staff.Role.ADMIN).build()));
        Order o = Order.builder().channel(Order.Channel.counter).createdBy(5L).build();
        assertThat(resolver.bookedBy(o)).isEqualTo("Amit Shah");
    }

    @Test
    void fallsBackToEmailThenUnknown() {
        when(repo.findById(6L)).thenReturn(Optional.of(Staff.builder().id(6L).email("c@d.co").role(Staff.Role.COUNTER).build()));
        when(repo.findById(7L)).thenReturn(Optional.empty());
        assertThat(resolver.bookedBy(Order.builder().channel(Order.Channel.counter).createdBy(6L).build())).isEqualTo("c@d.co");
        assertThat(resolver.bookedBy(Order.builder().channel(Order.Channel.counter).createdBy(7L).build())).isEqualTo("Counter");
    }
}
