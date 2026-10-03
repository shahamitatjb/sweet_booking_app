package com.jb.web;

import com.jb.service.FieldValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestParsingTest {

    @Test
    void parsesWholeNumberQuantities() {
        var lines = RequestParsing.parseLines(Map.of("items", List.of(
                Map.of("itemId", 1, "quantity", 2),
                Map.of("itemId", "2", "quantity", "3"))));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1).itemId()).isEqualTo(2L);
        assertThat(lines.get(1).quantity()).isEqualTo(3);
    }

    @Test
    void rejectsFractionalNegativeAndTextQuantities() {
        for (Object bad : List.of("2.5", -1, "abc", 2.0)) {
            assertThatThrownBy(() -> RequestParsing.parseLines(Map.of("items", List.of(Map.of("itemId", 1, "quantity", bad)))))
                    .isInstanceOf(FieldValidationException.class)
                    .extracting(e -> ((FieldValidationException) e).getField()).isEqualTo("items");
        }
    }

    @Test
    void rejectsMissingOrNonListItems() {
        assertThatThrownBy(() -> RequestParsing.parseLines(Map.of()))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> RequestParsing.parseLines(Map.of("items", "nope")))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void parsesCustomerAndTrimsStrings() {
        var c = RequestParsing.parseCustomer(Map.of(
                "name", "  Asha ", "mobile", " 9876543210", "address", "12 MG Road ", "pinCode", "411001", "email", " a@b.co "));
        assertThat(c.name()).isEqualTo("Asha");
        assertThat(c.mobile()).isEqualTo("9876543210");
        assertThat(c.email()).isEqualTo("a@b.co");
    }

    @Test
    void errorBodyCarriesFieldWhenPresent() {
        assertThat(RequestParsing.errorBody(new FieldValidationException("pinCode", "bad pin")))
                .containsEntry("error", "bad pin").containsEntry("field", "pinCode");
        assertThat(RequestParsing.errorBody(new IllegalArgumentException("plain")))
                .containsEntry("error", "plain").doesNotContainKey("field");
    }
}
