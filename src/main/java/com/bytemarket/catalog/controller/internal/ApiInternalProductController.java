package com.bytemarket.catalog.controller.internal;

import com.bytemarket.catalog.model.InventoryMovement;
import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.repository.IInventoryMovementRepository;
import com.bytemarket.catalog.repository.IProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
public class ApiInternalProductController {

    @Autowired
    private IProductRepository productRepository;

    @Autowired
    private IInventoryMovementRepository inventoryMovementRepository;

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
     * Descuenta stock y deja el movimiento de inventario asociado al pedido.
     * Solo toca los productos con track_stock activo.
     */
    @PostMapping("/stock-decrement")
    @Transactional
    public ResponseEntity<?> decrementStock(@RequestBody Map<String, Object> body) {
        Long orderId = body.get("orderId") == null ? null : Long.valueOf(body.get("orderId").toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items == null) items = List.of();

        int applied = 0;
        for (Map<String, Object> item : items) {
            Integer productId = item.get("productId") == null ? null : Integer.valueOf(item.get("productId").toString());
            int quantity = item.get("quantity") == null ? 0 : Integer.parseInt(item.get("quantity").toString());
            if (productId == null || quantity <= 0) continue;

            Product p = productRepository.findById(productId).orElse(null);
            if (p == null || p.getTrackStock() == null || p.getTrackStock() != 1) continue;

            int actual = p.getStock() == null ? 0 : p.getStock();
            p.setStock(Math.max(0, actual - quantity));
            productRepository.save(p);

            InventoryMovement mov = new InventoryMovement();
            mov.setProduct(p);
            mov.setMovementType("exit");
            mov.setQuantity(quantity);
            mov.setDelta(-quantity);
            mov.setReason("Venta");
            mov.setRelatedOrderId(orderId);
            inventoryMovementRepository.save(mov);
            applied++;
        }

        return ResponseEntity.ok(Map.of("applied", applied));
    }

    /**
     * Devuelve stock a los productos de un pedido anulado, con su movimiento
     * de entrada para que el inventario siga siendo auditable.
     */
    @PostMapping("/stock-restore")
    @Transactional
    public ResponseEntity<?> restoreStock(@RequestBody Map<String, Object> body) {
        Long orderId = body.get("orderId") == null ? null : Long.valueOf(body.get("orderId").toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items == null) items = List.of();

        int applied = 0;
        for (Map<String, Object> item : items) {
            Integer productId = item.get("productId") == null ? null : Integer.valueOf(item.get("productId").toString());
            int quantity = item.get("quantity") == null ? 0 : Integer.parseInt(item.get("quantity").toString());
            if (productId == null || quantity <= 0) continue;

            Product p = productRepository.findById(productId).orElse(null);
            if (p == null || p.getTrackStock() == null || p.getTrackStock() != 1) continue;

            p.setStock((p.getStock() == null ? 0 : p.getStock()) + quantity);
            productRepository.save(p);

            InventoryMovement mov = new InventoryMovement();
            mov.setProduct(p);
            mov.setMovementType("entry");
            mov.setQuantity(quantity);
            mov.setDelta(quantity);
            mov.setReason("Devolución por pedido cancelado");
            mov.setRelatedOrderId(orderId);
            inventoryMovementRepository.save(mov);
            applied++;
        }

        return ResponseEntity.ok(Map.of("applied", applied));
    }

    /** Productos activos bajo el umbral de stock, para las alertas del panel. */
    @GetMapping("/low-stock")
    public List<Map<String, Object>> lowStock(@RequestParam(defaultValue = "10") int threshold) {
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
        return out;
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

    private Map<String, Object> snapshot(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("slug", p.getSlug());
        m.put("price", precioVigente(p));
        m.put("stock", p.getStock());
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
