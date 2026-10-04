package com.bytemarket.catalog.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generación de slugs.
 *
 * Cubre el bug que daba "Column 'slug' cannot be null": el panel no envía el
 * slug, así que si no se deriva del nombre la columna queda nula y el insert
 * falla con un 500 que no explica nada.
 */
class SlugUtilsTest {

    @ParameterizedTest
    @CsvSource({
        "Pantalla LCD iPhone 13,       pantalla-lcd-iphone-13",
        "Batería Ñandú Pro,            bateria-nandu-pro",
        "Audífonos & Cables,           audifonos-cables",
        "  espacios   de   sobra  ,    espacios-de-sobra",
        "MAYÚSCULAS Y TILDES ÁÉÍÓÚ,    mayusculas-y-tildes-aeiou",
        "Cable USB-C a Lightning 1m,   cable-usb-c-a-lightning-1m",
        "50% descuento!!,              50-descuento"
    })
    @DisplayName("normaliza tildes, ñ, símbolos y espacios")
    void normaliza(String entrada, String esperado) {
        assertEquals(esperado, SlugUtils.make(entrada.trim()));
    }

    @Test
    @DisplayName("un nombre sin caracteres utilizables da null, no un slug vacío")
    void sinCaracteresUtiles() {
        assertNull(SlugUtils.make("!!!"));
        assertNull(SlugUtils.make("---"));
        assertNull(SlugUtils.make("   "));
        assertNull(SlugUtils.make(null));
    }

    @Test
    @DisplayName("makeOrDefault nunca devuelve null: ahí estaba el insert fallido")
    void conValorPorDefecto() {
        assertEquals("producto", SlugUtils.makeOrDefault("!!!", "producto"));
        assertEquals("categoria", SlugUtils.makeOrDefault(null, "categoria"));
        assertEquals("pantalla-oled", SlugUtils.makeOrDefault("Pantalla OLED", "producto"));
    }

    @Test
    @DisplayName("no deja guiones al inicio ni al final")
    void sinGuionesEnLosBordes() {
        String slug = SlugUtils.make("  ¡¡Oferta!!  ");
        assertEquals("oferta", slug);
        assertFalse(slug.startsWith("-"));
        assertFalse(slug.endsWith("-"));
    }

    // ─── Desempate de duplicados ────────────────────────────────

    @Test
    @DisplayName("si el slug está libre lo devuelve tal cual")
    void slugLibre() {
        assertEquals("bateria", SlugUtils.unique("bateria", s -> false));
    }

    @Test
    @DisplayName("añade sufijo numérico cuando ya está tomado")
    void desempataDuplicados() {
        Set<String> tomados = Set.of("bateria", "bateria-2", "bateria-3");
        assertEquals("bateria-4", SlugUtils.unique("bateria", tomados::contains));
    }

    @Test
    @DisplayName("dos productos con el mismo nombre no chocan contra el UNIQUE")
    void mismoNombreDosVeces() {
        Set<String> tomados = Set.of("pantalla-oled-samsung-s22");
        String base = SlugUtils.make("Pantalla OLED Samsung S22");
        assertEquals("pantalla-oled-samsung-s22-2", SlugUtils.unique(base, tomados::contains));
    }

    @Test
    @DisplayName("unique(null) devuelve null sin romper")
    void uniqueConNull() {
        assertNull(SlugUtils.unique(null, s -> false));
    }
}
