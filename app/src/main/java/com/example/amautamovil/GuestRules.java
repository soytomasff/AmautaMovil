package com.example.amautamovil;

import java.text.Normalizer;
import java.util.Locale;

final class GuestRules {
    private GuestRules() { }

    static String validate(String name, String surname, String document,
                           String origin, String country, String profession, String phone) {
        if (name.isEmpty() || name.length() > 255) return "Ingresa un nombre de hasta 255 caracteres.";
        if (surname.isEmpty() || surname.length() > 255) return "Ingresa un apellido de hasta 255 caracteres.";
        if (!document.matches("[A-Za-z0-9-]{5,20}"))
            return "El documento debe tener entre 5 y 20 letras, números o guiones.";
        if (origin.length() > 255 || country.length() > 255 || profession.length() > 255
                || phone.length() > 255) return "Los campos adicionales admiten hasta 255 caracteres.";
        return null;
    }

    static boolean matches(String name, String surname, String document, String query) {
        String search = fold(query.trim());
        return search.isEmpty() || fold(name + " " + surname).contains(search)
                || fold(surname + " " + name).contains(search)
                || fold(document).contains(search);
    }

    static int compare(String surnameA, String nameA, String documentA,
                       String surnameB, String nameB, String documentB) {
        int result = fold(surnameA).compareTo(fold(surnameB));
        if (result != 0) return result;
        result = fold(nameA).compareTo(fold(nameB));
        return result != 0 ? result : fold(documentA).compareTo(fold(documentB));
    }

    private static String fold(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
}
