package com.jb.web;

import com.jb.service.FieldValidationException;
import com.jb.service.OrderService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict parsing of booking request bodies, shared by the public and staff controllers. */
final class RequestParsing {
    private RequestParsing() {}

    static List<OrderService.CartLine> parseLines(Map<String, Object> body) {
        Object raw = body.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new FieldValidationException("items", "At least one packet required");
        }
        List<OrderService.CartLine> lines = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new FieldValidationException("items", "Invalid item line");
            }
            lines.add(new OrderService.CartLine(parseItemId(m.get("itemId")), parseQuantity(m.get("quantity"))));
        }
        return lines;
    }

    private static long parseItemId(Object raw) {
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new FieldValidationException("items", "Invalid item");
        }
    }

    /** Quantities must be whole numbers of zero or more; nothing fractional or negative. */
    private static int parseQuantity(Object raw) {
        if (raw == null) return 0;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            long v = ((Number) raw).longValue();
            if (v < 0 || v > Integer.MAX_VALUE) throw badQuantity();
            return (int) v;
        }
        if (raw instanceof String s && s.trim().matches("\\d{1,9}")) {
            return Integer.parseInt(s.trim());
        }
        throw badQuantity();
    }

    private static FieldValidationException badQuantity() {
        return new FieldValidationException("items", "Quantity must be a whole number of 0 or more");
    }

    static OrderService.Customer parseCustomer(Map<String, Object> body) {
        return new OrderService.Customer(
                str(body, "name"), str(body, "mobile"), str(body, "address"), str(body, "pinCode"), str(body, "email"));
    }

    static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    /** JSON error body: always {@code error}; plus {@code field} when the failure is tied to one input. */
    static Map<String, String> errorBody(RuntimeException e) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("error", String.valueOf(e.getMessage()));
        if (e instanceof FieldValidationException f && f.getField() != null) {
            out.put("field", f.getField());
        }
        return out;
    }
}
