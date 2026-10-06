package com.jb.it;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Treasurer marks online and counter-UPI payments as reconciled against Razorpay / bank statements. */
class ReconciliationIT extends IntegrationTestBase {

    private void counter(String method, Object... itemIdQtyPairs) throws Exception {
        Map<String, Object> body = orderBody(itemIdQtyPairs);
        body.put("paymentMethod", method);
        if (method.equals("upi")) body.put("upiReference", "123456789012");
        staffPost(counter, "/api/staff/counter/bookings", body).andExpect(status().isOk());
    }

    private void online(Object... itemIdQtyPairs) throws Exception {
        var order = createOnlineOrder(itemIdQtyPairs);
        postJson("/api/public/payments/verify", checkoutSuccess(order, "pay_" + order.get("orderId").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void treasurerMarksAndUnmarksABookingWithAnOptionalNote() throws Exception {
        online(ladoo.getId(), 1);          // JB-0001
        counter("upi", barfi.getId(), 1);  // JB-0002
        counter("cash", ladoo.getId(), 1); // JB-0003

        staffGet(admin, "/api/admin/dashboard").andExpect(jsonPath("$.unreconciledCount").value(2));
        staffGet(admin, "/api/admin/bookings?unreconciled=true")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].bookingId").value("JB-0002"))
                .andExpect(jsonPath("$[1].bookingId").value("JB-0001"));

        staffPost(treasurer, "/api/admin/bookings/JB-0001/reconcile",
                Map.of("reconciled", true, "note", "  Settlement setl_123  "))
                .andExpect(status().isOk());

        staffGet(admin, "/api/admin/bookings?q=JB-0001")
                .andExpect(jsonPath("$[0].reconciled").value(true))
                .andExpect(jsonPath("$[0].reconciledBy").value("Tara Treasurer"))
                .andExpect(jsonPath("$[0].reconcileNote").value("Settlement setl_123"));
        staffGet(admin, "/api/admin/dashboard").andExpect(jsonPath("$.unreconciledCount").value(1));
        staffGet(admin, "/api/admin/bookings?unreconciled=true")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookingId").value("JB-0002"));

        // Super admin can undo a mistake.
        staffPost(superAdmin, "/api/admin/bookings/JB-0001/reconcile", Map.of("reconciled", false))
                .andExpect(status().isOk());
        staffGet(admin, "/api/admin/bookings?q=JB-0001")
                .andExpect(jsonPath("$[0].reconciled").value(false))
                .andExpect(jsonPath("$[0].reconciledBy").value(""))
                .andExpect(jsonPath("$[0].reconcileNote").value(""));
        assertThat(auditActions()).contains("booking_reconciled", "booking_unreconciled");
    }

    @Test
    void cashAndVoidedBookingsCannotBeReconciled() throws Exception {
        counter("cash", ladoo.getId(), 1); // JB-0001
        counter("upi", barfi.getId(), 1);  // JB-0002
        staffPost(admin, "/api/admin/bookings/JB-0002/void", Map.of()).andExpect(status().isOk());

        staffGet(admin, "/api/admin/bookings?q=JB-0001").andExpect(jsonPath("$[0].reconcilable").value(false));
        staffPost(treasurer, "/api/admin/bookings/JB-0001/reconcile", Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Only online and counter UPI payments are reconciled"));
        staffPost(treasurer, "/api/admin/bookings/JB-0002/reconcile", Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Voided bookings cannot be reconciled"));
        staffGet(admin, "/api/admin/dashboard").andExpect(jsonPath("$.unreconciledCount").value(0));
    }

    @Test
    void bulkReconcileMarksEligibleBookingsAndSkipsTheRest() throws Exception {
        online(ladoo.getId(), 1);          // JB-0001
        counter("upi", barfi.getId(), 1);  // JB-0002
        counter("cash", ladoo.getId(), 1); // JB-0003
        staffPost(treasurer, "/api/admin/bookings/JB-0002/reconcile", Map.of()).andExpect(status().isOk());

        staffPost(treasurer, "/api/admin/bookings/reconcile",
                Map.of("bookingIds", List.of("JB-0001", "JB-0002", "JB-0003", "JB-0404"), "note", "Bank stmt Oct"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciled.length()").value(1))
                .andExpect(jsonPath("$.reconciled[0]").value("JB-0001"))
                .andExpect(jsonPath("$.skipped.length()").value(3));
        staffGet(admin, "/api/admin/dashboard").andExpect(jsonPath("$.unreconciledCount").value(0));
        staffPost(treasurer, "/api/admin/bookings/reconcile", Map.of("bookingIds", List.of()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportCarriesTheReconciliationColumns() throws Exception {
        counter("upi", barfi.getId(), 1);  // JB-0001
        counter("cash", ladoo.getId(), 1); // JB-0002
        staffPost(treasurer, "/api/admin/bookings/JB-0001/reconcile", Map.of("note", "UTR ok")).andExpect(status().isOk());

        byte[] xlsx = staffPost(admin, "/api/admin/export", Map.of("bookingIds", List.of("JB-0001", "JB-0002")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getCell(14).getStringCellValue()).isEqualTo("Reconciled");
            assertThat(header.getCell(17).getStringCellValue()).isEqualTo("Reconcile note");
            assertThat(sheet.getRow(1).getCell(14).getStringCellValue()).isEqualTo("Yes");
            assertThat(sheet.getRow(1).getCell(15).getStringCellValue()).isEqualTo("Tara Treasurer");
            assertThat(sheet.getRow(1).getCell(16).getStringCellValue()).isNotBlank();
            assertThat(sheet.getRow(1).getCell(17).getStringCellValue()).isEqualTo("UTR ok");
            assertThat(sheet.getRow(2).getCell(14).getStringCellValue()).isEqualTo("N/A");
        }
    }
}
