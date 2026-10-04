package com.jb.service;

import com.jb.domain.Order;
import com.jb.domain.OrderItem;
import com.jb.repository.OrderItemRepository;
import com.jb.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExportService {
    private static final DateTimeFormatter IST =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public byte[] exportPaidBookings() {
        List<Order> orders = orderRepository.findAll().stream()
                .filter(o -> o.getStatus() == Order.Status.paid)
                .toList();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Bookings");
            Row header = sheet.createRow(0);
            String[] cols = {
                    "Booking ID", "Booked at (IST)", "Name", "Mobile", "Address", "Pin",
                    "Total packets", "Total kg", "Amount (INR)", "Channel", "Payment mode",
                    "Taken by", "Handed over"
            };
            for (int i = 0; i < cols.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(cols[i]);
            }
            int r = 1;
            for (Order o : orders) {
                Row row = sheet.createRow(r++);
                // Booking ID resolved via service-less join: store gateway ref; full ID needs Booking table
                row.createCell(0).setCellValue(o.getId().toString());
                row.createCell(1).setCellValue(IST.format(o.getCreatedAt()));
                row.createCell(2).setCellValue(o.getCustomerName());
                row.createCell(3).setCellValue(o.getMobile());
                row.createCell(4).setCellValue(o.getAddress());
                row.createCell(5).setCellValue(o.getPinCode());
                row.createCell(6).setCellValue(o.getTotalPackets());
                row.createCell(7).setCellValue(o.getTotalWeightKg().doubleValue());
                row.createCell(8).setCellValue(o.getTotalAmount() / 100.0);
                row.createCell(9).setCellValue(o.getChannel().name());
                row.createCell(10).setCellValue(o.getPaymentMethod() == null ? "" : o.getPaymentMethod().name());
                row.createCell(11).setCellValue(o.getCreatedBy() == null ? "" : String.valueOf(o.getCreatedBy()));
                row.createCell(12).setCellValue("");
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Excel export failed", e);
        }
    }

    public byte[] exportBookingsWithIds(List<String> bookingIds, BookingIdLookup lookup) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Bookings");
            Row header = sheet.createRow(0);
            String[] cols = {
                    "Booking ID", "Booked at (IST)", "Name", "Mobile", "Address", "Pin",
                    "Total packets", "Total kg", "Amount (INR)", "Channel", "Payment mode",
                    "Bank transaction ref", "Taken by", "Handed over"
            };
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            int r = 1;
            for (String bid : bookingIds) {
                var view = lookup.lookup(bid);
                if (view == null) continue;
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(view.bookingId());
                row.createCell(1).setCellValue(view.bookedAtIst());
                row.createCell(2).setCellValue(view.name() == null ? "" : view.name());
                row.createCell(3).setCellValue(view.mobile() == null ? "" : view.mobile());
                row.createCell(4).setCellValue(view.address() == null ? "" : view.address());
                row.createCell(5).setCellValue(view.pin() == null ? "" : view.pin());
                row.createCell(6).setCellValue(view.totalPackets());
                row.createCell(7).setCellValue(view.totalKg());
                row.createCell(8).setCellValue(view.totalAmount() / 100.0);
                row.createCell(9).setCellValue(view.channel());
                row.createCell(10).setCellValue(view.paymentMode() == null ? "" : view.paymentMode());
                row.createCell(11).setCellValue(view.transactionRef() == null ? "" : view.transactionRef());
                row.createCell(12).setCellValue(view.takenBy() == null ? "" : view.takenBy());
                row.createCell(13).setCellValue("");
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Excel export failed", e);
        }
    }

    public interface BookingIdLookup {
        ExportRow lookup(String bookingId);
    }

    public record ExportRow(
            String bookingId, String bookedAtIst, String name, String mobile, String address, String pin,
            int totalPackets, double totalKg, int totalAmount, String channel, String paymentMode,
            String transactionRef, String takenBy
    ) {}
}
