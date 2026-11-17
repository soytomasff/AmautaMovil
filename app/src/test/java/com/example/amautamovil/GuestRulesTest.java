package com.example.amautamovil;

import org.junit.Test;
import static org.junit.Assert.*;

public class GuestRulesTest {
    @Test public void mirrorsBackendRequiredFieldsAndDocumentFormat() {
        assertNull(GuestRules.validate("Ana", "Pérez", "ABC-123", "", "", "", ""));
        assertNotNull(GuestRules.validate("", "Pérez", "ABC-123", "", "", "", ""));
        assertNotNull(GuestRules.validate("Ana", "Pérez", "12 34", "", "", "", ""));
        assertNotNull(GuestRules.validate("Ana", "Pérez", "1234", "", "", "", ""));
        assertNotNull(GuestRules.validate("Ana", "Pérez", "ABC-123", "", "", "", "x".repeat(256)));
    }

    @Test public void searchesNamesAndDocumentsIgnoringCaseAndAccents() {
        assertTrue(GuestRules.matches("José", "García", "AB-12345", "jose garcia"));
        assertTrue(GuestRules.matches("José", "García", "AB-12345", "ab-123"));
        assertFalse(GuestRules.matches("José", "García", "AB-12345", "Ramírez"));
        assertTrue(GuestRules.compare("Alva", "Ana", "1", "Zeta", "Ana", "2") < 0);
    }
}
