package com.example.amautamovil;

import java.math.BigInteger;
import java.util.Locale;

/** UI grouping follows the backend's persisted and operational room states. */
final class RoomRules {
    static final int ALL = 0, FREE = 1, OCCUPIED = 2, CLEANING = 3, MAINTENANCE = 4;

    private RoomRules() { }

    static int compareNumbers(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        boolean aNumeric = a.matches("[0-9]+");
        boolean bNumeric = b.matches("[0-9]+");
        if (aNumeric && bNumeric) {
            int result = new BigInteger(a).compareTo(new BigInteger(b));
            return result != 0 ? result : a.compareTo(b);
        }
        if (aNumeric != bNumeric) return aNumeric ? -1 : 1;
        return a.compareToIgnoreCase(b);
    }

    static int category(String state, String operational, boolean cleaningPending) {
        String raw = normalize(state);
        String effective = normalize(operational);
        if (effective.isEmpty()) effective = raw;
        if (raw.equals("MANTENIMIENTO") || effective.equals("MANTENIMIENTO")) return MAINTENANCE;
        if (isOccupied(raw) || isOccupied(effective)) return OCCUPIED;
        if (raw.equals("PENDIENTE DE LIMPIEZA") || effective.equals("PENDIENTE DE LIMPIEZA")
                || cleaningPending) return CLEANING;
        if ((raw.equals("LIBRE") || raw.equals("DISPONIBLE"))
                && (effective.equals("LIBRE") || effective.equals("DISPONIBLE"))) return FREE;
        return ALL;
    }

    private static boolean isOccupied(String state) {
        return state.equals("OCUPADO") || state.equals("OCUPADA")
                || state.equals("PARCIALMENTE OCUPADA") || state.equals("SALIDA PENDIENTE")
                || state.equals("SALIDA_PENDIENTE");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
