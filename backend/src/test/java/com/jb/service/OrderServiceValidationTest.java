package com.jb.service;

import com.jb.repository.ItemRepository;
import com.jb.repository.OrderItemRepository;
import com.jb.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OrderServiceValidationTest {
    private final SettingsService settings = mock(SettingsService.class);
    private final OrderService service = new OrderService(
            mock(ItemRepository.class), mock(OrderRepository.class), mock(OrderItemRepository.class), settings);

    private static OrderService.Customer valid() {
        return new OrderService.Customer("Asha Patel", "9876543210", "12 MG Road, Pune", "411001", "");
    }

    @Test
    void validCustomerPasses() {
        assertThatCode(() -> service.validateCustomer(valid(), false)).doesNotThrowAnyException();
    }

    @Test
    void shortNameIsRejectedWithFieldName() {
        var c = new OrderService.Customer("Al", "9876543210", "12 MG Road, Pune", "411001", "");
        assertThatThrownBy(() -> service.validateCustomer(c, false))
                .isInstanceOf(FieldValidationException.class)
                .extracting(e -> ((FieldValidationException) e).getField()).isEqualTo("name");
    }

    @Test
    void badMobileIsRejectedWithFieldName() {
        var c = new OrderService.Customer("Asha Patel", "1234567890", "12 MG Road, Pune", "411001", "");
        assertThatThrownBy(() -> service.validateCustomer(c, false))
                .isInstanceOf(FieldValidationException.class)
                .extracting(e -> ((FieldValidationException) e).getField()).isEqualTo("mobile");
    }

    @Test
    void shortAddressIsRejectedWithFieldName() {
        var c = new OrderService.Customer("Asha Patel", "9876543210", "Pune", "411001", "");
        assertThatThrownBy(() -> service.validateCustomer(c, false))
                .extracting(e -> ((FieldValidationException) e).getField()).isEqualTo("address");
    }

    @Test
    void badPinIsRejectedWithFieldName() {
        var c = new OrderService.Customer("Asha Patel", "9876543210", "12 MG Road, Pune", "41100", "");
        assertThatThrownBy(() -> service.validateCustomer(c, false))
                .extracting(e -> ((FieldValidationException) e).getField()).isEqualTo("pinCode");
    }

    @Test
    void badEmailIsRejectedWithFieldName() {
        var c = new OrderService.Customer("Asha Patel", "9876543210", "12 MG Road, Pune", "411001", "not-an-email");
        assertThatThrownBy(() -> service.validateCustomer(c, false))
                .extracting(e -> ((FieldValidationException) e).getField()).isEqualTo("email");
    }

    @Test
    void blankEmailIsRejectedOnlyWhenRequired() {
        assertThatCode(() -> service.validateCustomer(valid(), false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateCustomer(valid(), true))
                .extracting(e -> ((FieldValidationException) e).getField()).isEqualTo("email");
    }
}
