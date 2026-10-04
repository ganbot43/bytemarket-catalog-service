package com.bytemarket.catalog.service;

import java.text.Normalizer;
import java.util.function.Predicate;

/**
 * Generación de slugs para productos, categorías y subcategorías.
 *
 * El slug se deriva SIEMPRE del nombre en el servidor y nunca se toma del
 * cuerpo de la petición: la columna es NOT NULL y UNIQUE, y el panel no lo
 * envía, así que confiar en el cliente dejaba el campo en null y el insert
 * fallaba con "Column 'slug' cannot be null".
 *
 * Equivale al makeSlug del proyecto de referencia —slugify(texto, {lower,
 * strict, locale: 'es'})— para que las URLs no cambien de forma.
 */
public final class SlugUtils {

    private SlugUtils() {}

    /** "Pantalla LCD iPhone 13 Ñandú" -> "pantalla-lcd-iphone-13-nandu" */
    public static String make(String texto) {
        if (texto == null) return null;
        String base = Normalizer.normalize(texto, Normalizer.Form.NFD)
                // Quita tildes y la virgulilla: ñ -> n, á -> a.
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase()
                // Todo lo que no sea alfanumérico pasa a ser separador.
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return base.isBlank() ? null : base;
    }

    /**
     * Como make, pero nunca devuelve null: un nombre formado solo por
     * símbolos ("!!!", "---") no deja ningún carácter utilizable y volvería
     * a dejar la columna en null, que es justo el error a evitar.
     */
    public static String makeOrDefault(String texto, String porDefecto) {
        String slug = make(texto);
        return slug != null ? slug : porDefecto;
    }

    /**
     * Añade un sufijo numérico hasta encontrar un slug libre.
     *
     * El proyecto de referencia no hacía esto y dos nombres iguales chocaban
     * contra la restricción UNIQUE, produciendo el mismo 500 opaco que este
     * arreglo busca eliminar.
     *
     * @param yaExiste debe indicar si el slug está tomado por OTRO registro
     *                 (al editar, hay que excluir el propio).
     */
    public static String unique(String base, Predicate<String> yaExiste) {
        if (base == null) return null;
        String slug = base;
        // Tope defensivo: nunca debería hacer falta, pero evita un bucle
        // infinito si el predicado se comporta mal.
        for (int n = 2; n < 1000; n++) {
            if (!yaExiste.test(slug)) return slug;
            slug = base + "-" + n;
        }
        return base + "-" + System.currentTimeMillis();
    }
}
