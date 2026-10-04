package com.bytemarket.catalog.controller.api.admin;

import com.bytemarket.catalog.model.InventoryMovement;
import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.repository.IInventoryMovementRepository;
import com.bytemarket.catalog.repository.IProductRepository;
import com.bytemarket.catalog.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
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
public class ApiAdminInventoryController {

    @Autowired
    private IInventoryMovementRepository movementRepository;

    @Autowired
    private IProductRepository productRepository;

    @GetMapping("/movements")
    public Map<String, Object> movements(@RequestParam(defaultValue = "20") int limit,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(required = false) Integer productId,
                                         @RequestParam(required = false) String type) {
        int tamano = Math.max(1, limit);
        Pageable pagina = PageRequest.of(offset / tamano, tamano, Sort.by(Sort.Direction.DESC, "createdAt"));

        boolean hayTipo = type != null && !type.isBlank();
        Page<InventoryMovement> page;
        if (productId != null && hayTipo) {
            page = movementRepository.findByProductIdAndMovementType(productId, type, pagina);
        } else if (productId != null) {
            page = movementRepository.findByProductId(productId, pagina);
        } else if (hayTipo) {
            page = movementRepository.findByMovementType(type, pagina);
        } else {
            page = movementRepository.findAll(pagina);
        }

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
        p.setStock(Math.max(0, actual + delta));
        productRepository.save(p);

        InventoryMovement mov = new InventoryMovement();
        mov.setProduct(p);
        mov.setMovementType(tipo);
        mov.setQuantity(cantidad);
        mov.setDelta(delta);
        mov.setReason(texto(dto.get("reason")));
        mov.setCreatedById(CurrentUser.get().map(u -> u.id()).orElse(null));
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
        // El nombre del autor vive en user-service; el panel ya cae al id si falta.
        out.put("createdByName", null);
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
