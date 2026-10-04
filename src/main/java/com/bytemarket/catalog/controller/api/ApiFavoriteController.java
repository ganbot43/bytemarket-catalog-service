package com.bytemarket.catalog.controller.api;

import com.bytemarket.catalog.model.Favorite;
import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.repository.FavoriteRepository;
import com.bytemarket.catalog.repository.IProductRepository;
import com.bytemarket.catalog.security.AuthUser;
import com.bytemarket.catalog.security.CurrentUser;
import com.bytemarket.catalog.service.ProductPresenter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Favoritos de la cuenta.
 *
 * El frontend ya guarda los favoritos en el navegador (stores/favorites.ts)
 * a propósito: quien compra repuestos suele llegar sin sesión, comparar y
 * volver días después. Esto no reemplaza esa lista, la complementa: al
 * iniciar sesión, /sync fusiona lo local con la cuenta para que se conserve
 * al cambiar de dispositivo.
 */
@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
public class ApiFavoriteController {

    private final FavoriteRepository favoriteRepository;
    private final IProductRepository productRepository;
    private final ProductPresenter productPresenter;

    @GetMapping
    public ResponseEntity<?> list() {
        Optional<AuthUser> auth = CurrentUser.get();
        if (auth.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "Inicia sesión para ver tus favoritos");

        List<Product> productos = favoriteRepository.findProductsByUserId(auth.get().id());
        return ResponseEntity.ok(Map.of("data", productPresenter.toList(productos)));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> add(@RequestBody Map<String, Object> body) {
        Optional<AuthUser> auth = CurrentUser.get();
        if (auth.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "Inicia sesión para guardar favoritos");

        if (body.get("productId") == null) return error(HttpStatus.BAD_REQUEST, "Falta el producto");
        Product p = productRepository.findById(Integer.valueOf(body.get("productId").toString())).orElse(null);
        if (p == null) return error(HttpStatus.NOT_FOUND, "El producto no existe");

        Long uid = auth.get().id();
        // Repetir el alta no es un error: el botón es un interruptor y
        // pulsarlo dos veces no debería devolver un fallo.
        if (!favoriteRepository.existsByUserIdAndProduct(uid, p)) {
            favoriteRepository.save(new Favorite(uid, p));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("favorite", true));
    }

    @DeleteMapping("/{productId}")
    @Transactional
    public ResponseEntity<?> remove(@PathVariable Integer productId) {
        Optional<AuthUser> auth = CurrentUser.get();
        if (auth.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "Inicia sesión para quitar favoritos");

        Product p = productRepository.findById(productId).orElse(null);
        if (p == null) return error(HttpStatus.NOT_FOUND, "El producto no existe");

        favoriteRepository.deleteByUserIdAndProduct(auth.get().id(), p);
        return ResponseEntity.noContent().build();
    }

    /**
     * Fusiona la lista del navegador con la de la cuenta al iniciar sesión.
     *
     * Fusiona, no reemplaza: si se sobrescribiera con lo local, abrir la web
     * en un móvil nuevo borraría los favoritos guardados desde el ordenador.
     * Devuelve la lista final para que el navegador se ponga al día.
     */
    @PostMapping("/sync")
    @Transactional
    public ResponseEntity<?> sync(@RequestBody Map<String, Object> body) {
        Optional<AuthUser> auth = CurrentUser.get();
        if (auth.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "Inicia sesión para sincronizar favoritos");
        Long uid = auth.get().id();

        List<Integer> ids = new ArrayList<>();
        if (body.get("productIds") instanceof List<?> lista) {
            for (Object o : lista) {
                if (o == null) continue;
                try { ids.add(Integer.valueOf(o.toString())); } catch (NumberFormatException ignored) { }
            }
        }

        int agregados = 0;
        for (Integer id : ids) {
            Product p = productRepository.findById(id).orElse(null);
            // Un id que ya no existe se ignora en silencio: la lista local
            // puede ser de hace meses y tener productos ya retirados.
            if (p == null || favoriteRepository.existsByUserIdAndProduct(uid, p)) continue;
            favoriteRepository.save(new Favorite(uid, p));
            agregados++;
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("added", agregados);
        resp.put("data", productPresenter.toList(favoriteRepository.findProductsByUserId(uid)));
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("statusCode", status.value(), "message", message));
    }
}
