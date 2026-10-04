package com.bytemarket.catalog.controller.api;

import com.bytemarket.catalog.model.Review;
import com.bytemarket.catalog.repository.IProductRepository;
import com.bytemarket.catalog.repository.IReviewRepository;
import com.bytemarket.catalog.security.AuthUser;
import com.bytemarket.catalog.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reseñas de producto.
 *
 * Solo se publican las aprobadas: una reseña recién escrita queda en
 * "pending" hasta que el panel la revise, porque es texto de terceros que
 * se muestra en la ficha pública.
 */
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ApiReviewController {

    private final IReviewRepository reviewRepository;
    private final IProductRepository productRepository;

    /** Reseñas aprobadas de un producto, con su promedio. */
    @GetMapping
    public ResponseEntity<?> porProducto(@RequestParam Integer productId) {
        if (productId == null) return error(HttpStatus.BAD_REQUEST, "Falta el producto");

        List<Review> aprobadas = reviewRepository.findApprovedByProductId(productId);
        Double promedio = reviewRepository.getAverageRatingForProduct(productId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", aprobadas.stream().map(ApiReviewController::dtoPublico).toList());
        // Una cifra con un decimal: "4.3" dice lo mismo que 4.333333.
        resp.put("average", promedio == null ? 0d : Math.round(promedio * 10d) / 10d);
        resp.put("total", aprobadas.size());
        return ResponseEntity.ok(resp);
    }

    /** Productos mejor valorados, para la portada. */
    @GetMapping("/top")
    public Map<String, Object> top() {
        return Map.of("data", reviewRepository.findTopRankedProducts());
    }

    /** Deja una reseña. Requiere sesión: una reseña anónima no vale nada. */
    @PostMapping
    public ResponseEntity<?> crear(@RequestBody Map<String, Object> body) {
        Optional<AuthUser> auth = CurrentUser.get();
        if (auth.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "Inicia sesión para dejar tu reseña");

        if (body.get("productId") == null) return error(HttpStatus.BAD_REQUEST, "Falta el producto");
        Integer productId = Integer.valueOf(body.get("productId").toString());
        if (!productRepository.existsById(productId)) {
            return error(HttpStatus.NOT_FOUND, "El producto no existe");
        }

        if (body.get("rating") == null) return error(HttpStatus.BAD_REQUEST, "Falta la puntuación");
        int rating = Integer.parseInt(body.get("rating").toString());
        if (rating < 1 || rating > 5) return error(HttpStatus.BAD_REQUEST, "La puntuación va de 1 a 5");

        Long uid = auth.get().id();
        if (uid != null && reviewRepository.existsByUserIdAndProductId(uid, productId)) {
            return error(HttpStatus.CONFLICT, "Ya dejaste una reseña de este producto");
        }

        String comentario = texto(body.get("comment"));
        if (comentario != null && comentario.length() > 1000) {
            return error(HttpStatus.BAD_REQUEST, "El comentario es demasiado largo");
        }

        Review r = new Review();
        r.setUserId(uid);
        r.setProductId(productId);
        r.setRating(rating);
        r.setComment(comentario);
        r.setStatus("pending");
        reviewRepository.save(r);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Gracias. Tu reseña se publicará cuando la revisemos.",
                "status", "pending"));
    }

    /** Sin el userId: la ficha pública no tiene por qué exponer quién la escribió. */
    static Map<String, Object> dtoPublico(Review r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("rating", r.getRating());
        m.put("comment", r.getComment());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    static String texto(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("statusCode", status.value(), "message", message));
    }
}
