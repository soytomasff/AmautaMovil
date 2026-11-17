package com.example.amautamovil;

import java.text.Normalizer;
import java.util.GregorianCalendar;
import java.util.Locale;

final class ReservationRules {
    private ReservationRules() { }

    static boolean validDate(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return false;
        try {
            GregorianCalendar date = new GregorianCalendar(
                    Integer.parseInt(value.substring(0, 4)),
                    Integer.parseInt(value.substring(5, 7)) - 1,
                    Integer.parseInt(value.substring(8, 10)));
            date.setLenient(false);
            date.getTime();
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static boolean validRange(String entry, String exit) {
        return validDate(entry) && validDate(exit) && exit.compareTo(entry) > 0;
    }

    static boolean overlaps(String entry, String exit, String otherEntry, String otherExit) {
        return validRange(entry, exit) && validRange(otherEntry, otherExit)
                && entry.compareTo(otherExit) < 0 && exit.compareTo(otherEntry) > 0;
    }

    static boolean matches(String guest, String document, long id, String query) {
        String q = fold(query == null ? "" : query.trim());
        return q.isEmpty() || fold(guest).contains(q) || fold(document).contains(q)
                || String.valueOf(id).contains(q);
    }

    private static String fold(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
}
