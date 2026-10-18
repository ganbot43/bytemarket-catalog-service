package com.bytemarket.catalog.controller.internal;

import lombok.RequiredArgsConstructor;
import com.bytemarket.catalog.model.InventoryMovement;
import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.repository.IInventoryMovementRepository;
import com.bytemarket.catalog.repository.IProductRepository;
import com.bytemarket.catalog.service.ReservationService;
import com.bytemarket.catalog.service.StockService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API interna: la consume order-service por Feign para resolver precios y
 * descontar stock. No se publica en el gateway (ver SecurityConfig), así que
 * el precio que se cobra nunca lo decide el cliente.
 */
@RestController
@RequestMapping("/api/internal/products")
@RequiredArgsConstructor
public class ApiInternalProductController {

    private final IProductRepository productRepository;

    private final StockService stockService;

    private final ReservationService reservationService;

    private final IInventoryMovementRepository inventoryMovementRepository;

    /**
     * Snapshot de los productos de un carrito: nombre, precio vigente y stock.
     * order-service lo necesita para calcular el total sin confiar en el
     * precio que mande el navegador.
     */
    @PostMapping("/resolve")
    public List<Map<String, Object>> resolve(@RequestBody List<Integer> productIds) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (productIds == null) return out;
        for (Integer id : productIds) {
            if (id == null) continue;
            productRepository.findById(id).ifPresent(p -> out.add(snapshot(p)));
        }
        return out;
    }

    /**
     * Aparta las unidades de un pedido que se está cerrando.
     *
     * Es lo primero que hace el checkout, antes de guardar nada: si no
     * alcanza, el pedido no llega a existir. Al revés —guardar y luego
     * mirar— ya sería tarde.
     *
     * 409 y no 400 cuando falta stock: no es que la petición esté mal
     * formada, es que el mundo cambió entre que el cliente miró y pulsó.
     */
    @PostMapping("/stock-reserve")
    public ResponseEntity<?> reserveStock(@RequestBody Map<String, Object> body) {
        String orderCode = body.get("orderCode") == null ? null : body.get("orderCode").toString();
        try {
            reservationService.reservar(orderCode, items(body));
            return ResponseEntity.ok(Map.of("reserved", true, "orderCode", String.valueOf(orderCode)));
        } catch (ReservationService.SinStockException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("statusCode", 409, "message", e.getMessage()));
        }
    }

    /**
     * Devuelve lo apartado de un pedido que no salió adelante.
     *
     * Se llama cuando el pedido falla después de reservar. No es
     * imprescindible —las reservas caducan solas— pero devolver al momento
     * evita que el producto figure agotado durante el plazo de gracia por
     * un pedido que ya se sabe que no existe.
     */
    @PostMapping("/stock-release")
    public ResponseEntity<?> releaseStock(@RequestBody Map<String, Object> body) {
        String orderCode = body.get("orderCode") == null ? null : body.get("orderCode").toString();
        return ResponseEntity.ok(Map.of("released", reservationService.liberar(orderCode)));
    }

    /**
     * Descuenta stock por una venta y deja su movimiento de inventario.
     *
     * La lógica vive en StockService porque esta ruta ya no es la única: el
     * camino normal es la cola (StockListener). Al delegar, esta entrada
     * hereda la idempotencia del servicio —repetir la llamada con el mismo
     * pedido ya no descuenta dos veces— y las dos rutas no pueden volver a
     * divergir.
     */
    @PostMapping("/stock-decrement")
    public ResponseEntity<?> decrementStock(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(Map.of("applied", stockService.descontar(orderId(body), items(body))));
    }

    /**
     * Devuelve stock a los productos de un pedido anulado, con su movimiento
     * de entrada para que el inventario siga siendo auditable. Delega en
     * StockService por el mismo motivo que el descuento.
     */
    @PostMapping("/stock-restore")
    public ResponseEntity<?> restoreStock(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(Map.of("applied", stockService.devolver(orderId(body), items(body))));
    }

    /**
     * Productos activos bajo el umbral de stock, para las alertas del panel.
     *
     * Devuelve también el umbral aplicado, y no solo la lista, porque quien
     * llama (order-service) ya no manda ninguno: la regla es de aquí, y el
     * panel necesita poder escribir "con 5 unidades o menos" sin tener su
     * propia copia del número.
     */
    @GetMapping("/low-stock")
    public Map<String, Object> lowStock(
            @RequestParam(defaultValue = Product.UMBRAL_STOCK_BAJO_TXT) int threshold) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : productRepository.findProductosBajoStock(threshold)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.get("id"));
            m.put("name", row.get("nombre"));
            m.put("stock", row.get("stock"));
            m.put("price", row.get("precio"));
            m.put("category", Map.of("name", row.get("categoria")));
            out.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", out);
        resp.put("threshold", threshold);
        return resp;
    }

    /**
     * Rellena los movimientos de pedidos antiguos que nunca los registraron.
     * No toca el stock: reconstruye el historial, no lo vuelve a aplicar.
     */
    @PostMapping("/movements/backfill")
    @Transactional
    public ResponseEntity<?> backfillMovements(@RequestBody List<Map<String, Object>> entradas) {
        int creados = 0;
        if (entradas == null) entradas = List.of();

        for (Map<String, Object> e : entradas) {
            Long orderId = e.get("orderId") == null ? null : Long.valueOf(e.get("orderId").toString());
            Integer productId = e.get("productId") == null ? null : Integer.valueOf(e.get("productId").toString());
            int quantity = e.get("quantity") == null ? 0 : Integer.parseInt(e.get("quantity").toString());
            if (orderId == null || productId == null || quantity <= 0) continue;

            if (inventoryMovementRepository.existsByRelatedOrderIdAndProductId(orderId, productId)) continue;

            Product p = productRepository.findById(productId).orElse(null);
            if (p == null) continue;

            InventoryMovement mov = new InventoryMovement();
            mov.setProduct(p);
            mov.setMovementType("exit");
            mov.setQuantity(quantity);
            mov.setDelta(-quantity);
            mov.setReason("Venta (reconstruido)");
            mov.setRelatedOrderId(orderId);
            inventoryMovementRepository.save(mov);
            creados++;
        }

        return ResponseEntity.ok(Map.of("created", creados));
    }

    /** Inventario completo para la exportación Excel/PDF del panel. */
    @GetMapping("/export")
    public List<Map<String, Object>> export() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Product p : productRepository.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("price", p.getPrice());
            m.put("stock", p.getStock());
            m.put("isActive", p.getIsActive() != null && p.getIsActive() == 1);
            m.put("createdAt", p.getCreatedAt());
            m.put("category", p.getCategory() == null ? null : Map.of("name", p.getCategory().getName()));
            out.add(m);
        }
        return out;
    }

    private static Long orderId(Map<String, Object> body) {
        Object v = body.get("orderId");
        return v == null ? null : Long.valueOf(v.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> body) {
        Object raw = body.get("items");
        return raw instanceof List ? (List<Map<String, Object>>) raw : List.of();
    }

    private Map<String, Object> snapshot(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("slug", p.getSlug());
        m.put("price", precioVigente(p));
        /*
         * `stock` aquí es lo DISPONIBLE, no lo que hay en el almacén.
         *
         * Quien consume esto es la validación del checkout, y lo que
         * necesita saber es cuánto puede prometer: lo que hay menos lo ya
         * comprometido con otros pedidos. Mandar la cifra bruta hacía que
         * el mensaje de "solo quedan N" contara unidades que ya tenían
         * dueño.
         *
         * La cifra del almacén va aparte, por si alguien la necesita.
         */
        int enAlmacen = p.getStock() == null ? 0 : p.getStock();
        int comprometido = p.getReserved() == null ? 0 : p.getReserved();
        m.put("stock", Math.max(0, enAlmacen - comprometido));
        m.put("stockEnAlmacen", enAlmacen);
        m.put("reserved", comprometido);
        m.put("trackStock", p.getTrackStock() != null && p.getTrackStock() == 1);
        m.put("isActive", p.getIsActive() != null && p.getIsActive() == 1);
        return m;
    }

    /** El precio de oferta manda solo si existe y es menor que el de lista. */
    private double precioVigente(Product p) {
        double base = p.getPrice() == null ? 0d : p.getPrice();
        Double oferta = p.getDiscountPrice();
        return (oferta != null && oferta > 0 && oferta < base) ? oferta : base;
    }
}
