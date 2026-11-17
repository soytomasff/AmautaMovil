package com.example.amautamovil;

import org.junit.Test;
import static org.junit.Assert.*;

public class RoomRulesTest {
    @Test public void numbersSortNumerically() {
        assertTrue(RoomRules.compareNumbers("2", "10") < 0);
        assertTrue(RoomRules.compareNumbers("101", "9") > 0);
        assertTrue(RoomRules.compareNumbers("002", "2") < 0);
    }

    @Test public void checkoutNeverMarksRoomFreeBeforeCleaning() {
        assertEquals(RoomRules.OCCUPIED, RoomRules.category("OCUPADO", "SALIDA PENDIENTE", false));
        assertEquals(RoomRules.OCCUPIED, RoomRules.category("PARCIALMENTE OCUPADA", "PARCIALMENTE OCUPADA", true));
        assertEquals(RoomRules.CLEANING, RoomRules.category("PENDIENTE DE LIMPIEZA", null, true));
        assertEquals(RoomRules.MAINTENANCE, RoomRules.category("MANTENIMIENTO", null, false));
        assertEquals(RoomRules.FREE, RoomRules.category("LIBRE", "LIBRE", false));
        assertNotEquals(RoomRules.FREE, RoomRules.category("LIBRE", "LIBRE", true));
    }
}
