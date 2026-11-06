package com.example.jobscheduler.util;

import java.nio.charset.StandardCharsets;

public final class TextLimit {
    private TextLimit() {
    }

    /** PostgreSQL text cannot contain NUL; cut only at a UTF-8 character boundary. */
    public static String truncate(String text) {
        if (text == null) {
            return null;
        }
        var bytes = text.replace("\u0000", "").getBytes(StandardCharsets.UTF_8);
        int end = Math.min(bytes.length, 4096);
        while (end > 0 && end < bytes.length && (bytes[end] & 0xc0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
