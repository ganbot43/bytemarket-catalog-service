package com.bytemarket.catalog.controller.api.admin;

import com.bytemarket.catalog.model.Category;
import com.bytemarket.catalog.repository.ICategoryRepository;
import com.bytemarket.catalog.service.SlugUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/categories")
public class ApiAdminCategoryController {

    @Autowired
    private ICategoryRepository categoryRepository;

    @Autowired
    private com.bytemarket.catalog.service.S3Service s3Service;

    @GetMapping
    public Map<String, Object> getAll() {
        List<Category> list = categoryRepository.findAll();
        List<Map<String, Object>> data = list.stream().map(c -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("slug", c.getSlug());
            m.put("sortOrder", c.getSortOrder());
            // Booleano, como el resto de la API: en base es 0/1.
            m.put("isActive", c.getIsActive() != null && c.getIsActive() == 1);
            m.put("seccion", c.getSeccion());
            m.put("createdAt", c.getCreatedAt());
            // El formulario de edición lo lee; sin esto se perdía al editar.
            m.put("idProducto", c.getIdProducto());
            // Faltaba: el panel no podía mostrar la imagen ya guardada.
            m.put("imagenBanner", s3Service.getPresignedUrl(c.getImagenBanner()));
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> resp = new HashMap<>();
        resp.put("data", data);
        return resp;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Category dto) {
        if (dto.getName() == null || dto.getName().trim().length() < 2) {
            return ResponseEntity.badRequest()
                    .body(Map.of("statusCode", 400, "message", "El nombre de la categoría es obligatorio"));
        }
        dto.setId(null);
        if (dto.getIsActive() == null) dto.setIsActive(1);
        // Sin firma: el panel reenvía la URL firmada que recibió al leer.
        dto.setImagenBanner(s3Service.normalizeUrl(dto.getImagenBanner()));
        aplicarSlug(dto);
        Category saved = categoryRepository.save(dto);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Category dto) {
        return categoryRepository.findById(id).map(c -> {
            if(dto.getName() != null) c.setName(dto.getName());
            if(dto.getSortOrder() != null) c.setSortOrder(dto.getSortOrder());
            if(dto.getIsActive() != null) c.setIsActive(dto.getIsActive());
            if(dto.getSeccion() != null) c.setSeccion(dto.getSeccion());
            if(dto.getIdProducto() != null) c.setIdProducto(dto.getIdProducto());
            // Faltaba copiarla: la imagen subida se descartaba al editar.
            if(dto.getImagenBanner() != null) {
                c.setImagenBanner(s3Service.normalizeUrl(dto.getImagenBanner()));
            }
            // Se recalcula solo si llegó un nombre nuevo, para no romper URLs
            // ya publicadas al guardar otros campos.
            if (dto.getName() != null || c.getSlug() == null || c.getSlug().isBlank()) {
                aplicarSlug(c);
            }
            return ResponseEntity.ok(categoryRepository.save(c));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * El slug se deriva del nombre y nunca se toma del cuerpo: el panel no lo
     * envía y la columna es NOT NULL UNIQUE, así que antes quedaba en null y
     * el insert fallaba con "Column 'slug' cannot be null".
     */
    private void aplicarSlug(Category c) {
        String base = SlugUtils.makeOrDefault(c.getName(), "categoria");
        final Integer propio = c.getId();
        c.setSlug(SlugUtils.unique(base, s ->
                categoryRepository.findBySlug(s)
                        .filter(otra -> !otra.getId().equals(propio))
                        .isPresent()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        categoryRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
