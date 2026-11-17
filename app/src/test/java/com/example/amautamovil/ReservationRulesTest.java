package com.example.amautamovil;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReservationRulesTest {
    @Test public void rejectsInvalidDatesAndAllowsAdjacentStays() {
        assertFalse(ReservationRules.validRange("2026-02-30", "2026-03-02"));
        assertFalse(ReservationRules.validRange("2026-04-02", "2026-04-02"));
        assertTrue(ReservationRules.validRange("2028-02-29", "2028-03-01"));
        assertFalse(ReservationRules.overlaps("2026-04-01", "2026-04-03",
                "2026-04-03", "2026-04-05"));
        assertTrue(ReservationRules.overlaps("2026-04-01", "2026-04-04",
                "2026-04-03", "2026-04-05"));
    }

    @Test public void searchUsesGuestDocumentAndId() {
        assertTrue(ReservationRules.matches("José Pérez", "AB-123", 42, "jose"));
        assertTrue(ReservationRules.matches("José Pérez", "AB-123", 42, "ab-1"));
        assertTrue(ReservationRules.matches("José Pérez", "AB-123", 42, "42"));
        assertFalse(ReservationRules.matches("José Pérez", "AB-123", 42, "Ramírez"));
    }
}
