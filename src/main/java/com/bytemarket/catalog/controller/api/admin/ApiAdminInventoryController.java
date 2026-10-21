package com.bytemarket.catalog.controller.api.admin;

import lombok.RequiredArgsConstructor;
import com.bytemarket.catalog.model.InventoryMovement;
import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.repository.IInventoryMovementRepository;
import com.bytemarket.catalog.repository.IProductRepository;
import com.bytemarket.catalog.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Kardex: consulta de movimientos y alta manual de entradas/salidas/ajustes. */
@RestController
@RequestMapping("/api/admin/inventory")
@RequiredArgsConstructor
public class ApiAdminInventoryController {

    private final IInventoryMovementRepository movementRepository;

    private final IProductRepository productRepository;

    /**
     * Existencias actuales, que es lo que faltaba en esta pantalla.
     *
     * El inventario solo mostraba MOVIMIENTOS: respondía "¿qué pasó?" pero
     * no "¿qué tengo que reponer?", y para saberlo había que ir a Productos
     * y mirar uno por uno. Esto lista el stock de cada producto ordenado de
     * menor a mayor, con el filtro de solo-bajos y el resumen de cuántos
     * faltan.
     *
     * Solo salen los que llevan control de stock: para el resto la cifra no
     * significaría nada.
     */
    @GetMapping("/stock")
    public Map<String, Object> stock(@RequestParam(defaultValue = "20") int limit,
                                     @RequestParam(defaultValue = "0") int offset,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = Product.UMBRAL_STOCK_BAJO_TXT) int threshold,
                                     @RequestParam(defaultValue = "0") int low) {
        int tamano = Math.max(1, limit);
        int umbral = Math.max(0, threshold);
        String patron = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";

        Page<Map<String, Object>> page = productRepository.listarExistencias(
                patron, low == 1 ? 1 : 0, umbral, PageRequest.of(offset / tamano, tamano));

        List<Map<String, Object>> data = page.getContent().stream().map(row -> {
            int stock = entero(row.get("stock"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.get("id"));
            m.put("name", row.get("name"));
            m.put("slug", row.get("slug"));
            m.put("stock", stock);
            m.put("price", row.get("price"));
            m.put("category", row.get("category"));
            m.put("isActive", entero(row.get("isActive")) == 1);
            // El estado se calcula aquí y no en el panel para que la alerta
            // del dashboard y esta lista usen el mismo umbral.
            m.put("estado", stock <= 0 ? "agotado" : stock <= umbral ? "bajo" : "ok");
            return m;
        }).toList();

        Map<String, Object> resumen = productRepository.resumenDeExistencias(umbral);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", data);
        resp.put("total", page.getTotalElements());
        resp.put("threshold", umbral);
        resp.put("lowCount", entero(resumen.get("bajos")));
        resp.put("outCount", entero(resumen.get("agotados")));
        resp.put("trackedCount", entero(resumen.get("total")));
        return resp;
    }

    private static int entero(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * El kardex, con los filtros del panel: producto, tipo y buscador.
     *
     * Los tres van a una sola consulta con parámetros opcionales. Antes era
     * una cadena de ifs que elegía entre cuatro métodos del repositorio, y
     * al añadir el buscador habrían sido ocho ramas para la misma pregunta.
     */
    @GetMapping("/movements")
    public Map<String, Object> movements(@RequestParam(defaultValue = "20") int limit,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(required = false) Integer productId,
                                         @RequestParam(required = false) String type,
                                         @RequestParam(required = false) String q) {
        int tamano = Math.max(1, limit);
        Pageable pagina = PageRequest.of(offset / tamano, tamano, Sort.by(Sort.Direction.DESC, "createdAt"));

        String tipo = (type == null || type.isBlank()) ? null : type;
        String patron = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";
        Page<InventoryMovement> page = movementRepository.buscar(productId, tipo, patron, pagina);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", page.getContent().stream().map(this::dto).toList());
        resp.put("total", page.getTotalElements());
        return resp;
    }

    @PostMapping("/movements")
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> dto) {
        if (dto.get("productId") == null) return error(HttpStatus.BAD_REQUEST, "Seleccione un producto");
        Product p = productRepository.findById(Integer.valueOf(dto.get("productId").toString())).orElse(null);
        if (p == null) return error(HttpStatus.NOT_FOUND, "Producto no encontrado");

        String tipo = dto.get("movementType") == null ? "entry" : dto.get("movementType").toString();
        if (!List.of("entry", "exit", "adjustment").contains(tipo)) {
            return error(HttpStatus.BAD_REQUEST, "Tipo de movimiento no válido");
        }
        int cantidad = dto.get("quantity") == null ? 0 : Integer.parseInt(dto.get("quantity").toString());
        if (cantidad <= 0) return error(HttpStatus.BAD_REQUEST, "La cantidad debe ser mayor que cero");

        // "adjustment" fija el stock al valor indicado; entry/exit lo mueven.
        int actual = p.getStock() == null ? 0 : p.getStock();
        int delta = switch (tipo) {
            case "entry" -> cantidad;
            case "exit" -> -cantidad;
            default -> cantidad - actual;
        };

        /*
         * Una salida mayor que la existencia se rechaza, no se recorta.
         *
         * Antes era `setStock(Math.max(0, actual + delta))` y el movimiento
         * guardaba el delta completo: sacar 5 de un stock de 2 dejaba la
         * columna en 0 y el kardex anotando -5. El histórico dejaba de
         * explicar la existencia y nadie se enteraba.
         *
         * Aquí no se recorta en silencio porque es una orden de una persona,
         * no un mensaje de la cola: si quien ajusta se equivoca de número,
         * lo que necesita es que se lo digan. Para una salida que de verdad
         * supera lo registrado —mermas que nadie anotó— está "Conté el
         * almacén", que fija la cifra real.
         */
        if (actual + delta < 0) {
            return error(HttpStatus.CONFLICT,
                    "No puedes sacar " + cantidad + " unidades: solo hay " + actual
                            + ". Si el almacén dice otra cosa, usa 'Conté el almacén' para fijar la cifra real.");
        }

        // La suma la hace la base de datos: leer, sumar y guardar son tres
        // pasos, y entre ellos cabe otra operación sobre el mismo producto.
        if (productRepository.moverStock(p.getId(), delta) == 0 && delta != 0) {
            return error(HttpStatus.CONFLICT,
                    "El stock cambió mientras se guardaba. Vuelve a intentarlo.");
        }

        InventoryMovement mov = new InventoryMovement();
        mov.setProduct(p);
        mov.setMovementType(tipo);
        mov.setQuantity(cantidad);
        mov.setDelta(delta);
        mov.setReason(texto(dto.get("reason")));
        mov.setCreatedById(CurrentUser.get().map(u -> u.id()).orElse(null));
        mov.setCreatedByName(CurrentUser.get().map(u -> u.name()).orElse(null));
        movementRepository.save(mov);

        return ResponseEntity.status(HttpStatus.CREATED).body(dto(mov));
    }

    private Map<String, Object> dto(InventoryMovement m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("productId", m.getProduct() == null ? null : m.getProduct().getId());
        out.put("productName", m.getProduct() == null ? null : m.getProduct().getName());
        out.put("movementType", m.getMovementType());
        out.put("quantity", m.getQuantity());
        out.put("delta", m.getDelta());
        out.put("reason", m.getReason());
        out.put("relatedOrderId", m.getRelatedOrderId());
        out.put("createdBy", m.getCreatedById());
        out.put("createdByName", m.getCreatedByName());
        /* Que no haya id significa que el movimiento no lo hizo una persona:
           lo escribió el consumidor de la cola, sin petición ni JWT. Se
           deriva aquí en vez de guardar un "Sistema" en la columna del
           nombre, porque esa columna lleva nombres que el propio usuario
           elige —y sin validación alguna—: un admin podría renombrarse
           "Sistema" y hacer pasar un ajuste a mano por uno automático. */
        out.put("createdBySystem", m.getCreatedById() == null);
        out.put("createdAt", m.getCreatedAt());
        return out;
    }

    private static String texto(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("statusCode", status.value(), "message", message));
    }
}
