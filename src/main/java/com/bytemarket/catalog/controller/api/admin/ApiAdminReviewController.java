package com.bytemarket.catalog.controller.api.admin;

import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.model.Review;
import com.bytemarket.catalog.repository.IProductRepository;
import com.bytemarket.catalog.repository.IReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Moderación de reseñas: aprobar o rechazar lo que escriben los clientes. */
@RestController
@RequestMapping("/api/admin/reviews")
@RequiredArgsConstructor
public class ApiAdminReviewController {

    private static final List<String> ESTADOS = List.of("pending", "approved", "rejected");

    private final IReviewRepository reviewRepository;
    private final IProductRepository productRepository;

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String q) {
        int tamano = Math.max(1, limit);
        Pageable pagina = PageRequest.of(offset / tamano, tamano);

        String estado = (status == null || status.isBlank()) ? null : status;
        String texto = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();

        Page<Review> page;
        if (texto == null) {
            page = estado == null
                    ? reviewRepository.findAllByOrderByCreatedAtDesc(pagina)
                    : reviewRepository.findByStatusOrderByCreatedAtDesc(estado, pagina);
        } else {
            String patron = "%" + texto + "%";
            // Se busca en el comentario y en el nombre del producto: el admin
            // normalmente recuerda qué producto era, no qué escribió el cliente.
            List<Integer> ids = productRepository.findIdsByNombreLike(patron);
            if (ids.isEmpty()) ids = List.of(-1); // "IN ()" no es SQL válido
            page = reviewRepository.buscar(estado, patron, ids, pagina);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", page.getContent().stream().map(this::dto).toList());
        resp.put("total", page.getTotalElements());
        return resp;
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<?> moderar(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        Review r = reviewRepository.findById(id).orElse(null);
        if (r == null) return error(HttpStatus.NOT_FOUND, "Reseña no encontrada");

        Object raw = body.get("status");
        String estado = raw == null ? null : String.valueOf(raw).trim();
        if (estado == null || !ESTADOS.contains(estado)) {
            return error(HttpStatus.BAD_REQUEST, "Estado no válido: usa pending, approved o rejected");
        }
        r.setStatus(estado);
        return ResponseEntity.ok(dto(reviewRepository.save(r)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!reviewRepository.existsById(id)) return error(HttpStatus.NOT_FOUND, "Reseña no encontrada");
        reviewRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Aquí sí va el autor y el producto: el panel necesita contexto para moderar. */
    private Map<String, Object> dto(Review r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("rating", r.getRating());
        m.put("comment", r.getComment());
        m.put("status", r.getStatus());
        m.put("userId", r.getUserId());
        m.put("productId", r.getProductId());
        m.put("productName", r.getProductId() == null ? null
                : productRepository.findById(r.getProductId()).map(Product::getName).orElse(null));
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("statusCode", status.value(), "message", message));
    }
}
