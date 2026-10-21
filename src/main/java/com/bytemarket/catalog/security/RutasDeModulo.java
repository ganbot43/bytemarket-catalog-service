package com.bytemarket.catalog.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * A qué módulo del panel pertenece cada ruta de /api/admin de ESTE servicio.
 *
 * Una regla puede declarar VARIOS módulos, y basta con tener uno: leer el
 * catálogo es el caso. El cajero hace GET de productos porque la pantalla de
 * caja lista y busca para vender —eso es el módulo "caja"—, pero el
 * encargado lo hace desde Productos. Si la ruta perteneciera a un solo
 * módulo, uno de los dos se quedaría fuera: atándola a "productos" el cajero
 * no podría cobrar, y atándola a "caja" conceder Productos a alguien no le
 * dejaría ver ninguno.
 *
 * El método decide cuál de los dos permisos hace falta: GET y HEAD consultan,
 * cualquier otra cosa escribe. Por eso escribir en productos sí es del módulo
 * Productos aunque leerlos también valga desde Caja.
 *
 * Gana la coincidencia más larga, así que lo específico va antes que el
 * comodín sin depender del orden de la lista.
 */
public final class RutasDeModulo {

    private RutasDeModulo() {}

    private record Regla(String prefijo, boolean soloLectura, List<String> modulos) {}

    private static final List<Regla> REGLAS = List.of(
            /* Leer el catálogo vale desde la caja o desde Productos. */
            new Regla("/api/admin/products", true, List.of("caja", "productos")),
            /* Escribirlo es solo de Productos. */
            new Regla("/api/admin/products", false, List.of("productos")),
            new Regla("/api/admin/inventory", false, List.of("inventario")),
            new Regla("/api/admin/categories", false, List.of("categorias")),
            new Regla("/api/admin/subcategories", false, List.of("subcategorias")),
            new Regla("/api/admin/banners", false, List.of("banners")),
            new Regla("/api/admin/reviews", false, List.of("resenas")));

    /** Los módulos que habilitan esta petición; vacío si la ruta no está declarada. */
    public static List<String> modulos(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean lectura = "view".equals(accion(request));

        List<String> mejor = List.of();
        int largo = -1;
        for (Regla r : REGLAS) {
            /* Una regla marcada soloLectura no aplica a una escritura; las
               demás valen para ambos. */
            if (r.soloLectura() && !lectura) continue;
            boolean casa = path.equals(r.prefijo()) || path.startsWith(r.prefijo() + "/");
            if (!casa) continue;
            /* A igual longitud gana la de solo lectura, que es la más
               específica: declara un caso que la general no cubre. */
            if (r.prefijo().length() > largo || (r.prefijo().length() == largo && r.soloLectura())) {
                mejor = r.modulos();
                largo = r.prefijo().length();
            }
        }
        return mejor;
    }

    /**
     * Qué acción pide esta petición, según el verbo.
     *
     * GET y HEAD consultan, POST da de alta, PUT y PATCH modifican, DELETE da
     * de baja. Sustituye a tener una regla por verbo en el SecurityConfig,
     * que es de donde salían los errores de "esta ruta se la llevó por
     * delante el comodín".
     *
     * Un POST que no crea nada —abrir la caja, cuadrar un turno— cuenta como
     * crear, y es lo correcto: son actos que dejan un registro nuevo, no
     * consultas.
     */
    public static String accion(HttpServletRequest request) {
        return switch (request.getMethod()) {
            case "POST" -> "create";
            case "PUT", "PATCH" -> "edit";
            case "DELETE" -> "delete";
            default -> "view";
        };
    }
}
