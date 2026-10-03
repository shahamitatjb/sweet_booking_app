package com.jb.service;

import com.jb.domain.Order;
import com.jb.domain.Staff;
import com.jb.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Display label for who took a booking: the staff member for counter bookings, "Online" otherwise. */
@Component
@RequiredArgsConstructor
public class BookedByResolver {
    public static final String ONLINE = "Online";
    private static final String UNKNOWN_COUNTER = "Counter";

    private final StaffRepository staffRepository;

    public String bookedBy(Order order) {
        if (order.getChannel() == Order.Channel.online || order.getCreatedBy() == null) return ONLINE;
        return staffRepository.findById(order.getCreatedBy())
                .map(BookedByResolver::displayName)
                .orElse(UNKNOWN_COUNTER);
    }

    public static String displayName(Staff s) {
        return s.getName() == null || s.getName().isBlank() ? s.getEmail() : s.getName();
    }
}
