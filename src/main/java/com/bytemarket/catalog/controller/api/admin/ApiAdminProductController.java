package com.bytemarket.catalog.controller.api.admin;

import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.service.ProductAdminService;
import com.bytemarket.catalog.service.ProductPresenter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mantenimiento de productos del panel.
 *
 * Aquí solo hay HTTP: leer parámetros, llamar al servicio y dar forma a la
 * respuesta. Las reglas —el slug, el kardex que impide borrar, las
 * imágenes— viven en ProductAdminService, y los errores viajan como
 * CatalogoException hasta el GlobalExceptionHandler en vez de resolverse
 * aquí con `return error(…)`.
 *
 * Antes esta clase tenía 225 líneas, cuatro repositorios inyectados y el
 * mapeo campo por campo metido en medio.
 */
@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class ApiAdminProductController {

    private final ProductAdminService productAdminService;
    private final ProductPresenter productPresenter;

    @GetMapping
    public Map<String, Object> getAll(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) Integer isActive) {

        ProductAdminService.PaginaDeProductos resultado =
                productAdminService.listar(limit, offset, q, categoryId, isActive);

        List<Map<String, Object>> data = resultado.pagina().getContent().stream()
                .map(productPresenter::filaAdmin)
                .toList();

        Map<String, Object> resp = new HashMap<>();
        resp.put("data", data);
        resp.put("total", resultado.pagina().getTotalElements());
        resp.put("totalActive", resultado.totalActivos());
        resp.put("totalInactive", resultado.totalInactivos());
        return resp;
    }

    @PostMapping
    public ResponseEntity<Product> create(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(productAdminService.crear(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> update(@PathVariable Integer id, @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(productAdminService.actualizar(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        productAdminService.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}
