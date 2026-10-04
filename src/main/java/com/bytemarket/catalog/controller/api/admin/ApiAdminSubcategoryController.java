package com.bytemarket.catalog.controller.api.admin;

import com.bytemarket.catalog.model.Subcategory;
import com.bytemarket.catalog.model.Category;
import com.bytemarket.catalog.repository.ISubcategoryRepository;
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
@RequestMapping("/api/admin/subcategories")
public class ApiAdminSubcategoryController {

    @Autowired
    private ISubcategoryRepository subcategoryRepository;
    
    @Autowired
    private ICategoryRepository categoryRepository;

    @GetMapping
    public Map<String, Object> getAll() {
        List<Subcategory> list = subcategoryRepository.findAll();
        List<Map<String, Object>> data = list.stream().map(c -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("slug", c.getSlug());
            m.put("sortOrder", c.getSortOrder());
            // El panel lee "isActive": con solo "active" leía undefined y
            // pintaba todas las subcategorías como inactivas. Se conserva
            // "active" por si algo ya dependía del nombre viejo.
            m.put("isActive", c.isActive());
            m.put("active", c.isActive());
            m.put("createdAt", c.getCreatedAt());
            m.put("categoryId", c.getCategory() != null ? c.getCategory().getId() : null);
            // La columna "Categoría" de la tabla lee category.name.
            m.put("category", c.getCategory() == null ? null
                    : Map.of("id", c.getCategory().getId(), "name", c.getCategory().getName()));
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> resp = new HashMap<>();
        resp.put("data", data);
        return resp;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> dto) {
        String nombre = dto.get("name") == null ? null : String.valueOf(dto.get("name")).trim();
        if (nombre == null || nombre.length() < 2) {
            return ResponseEntity.badRequest()
                    .body(Map.of("statusCode", 400, "message", "El nombre de la subcategoría es obligatorio"));
        }

        Subcategory s = new Subcategory();
        s.setName(nombre);
        if(dto.get("sortOrder") != null) s.setSortOrder(Integer.parseInt(dto.get("sortOrder").toString()));
        // El panel envía "isActive"; antes solo se leía "active", así que el
        // interruptor del formulario no se guardaba nunca.
        Object activo = dto.get("isActive") != null ? dto.get("isActive") : dto.get("active");
        if(activo != null) s.setActive(Boolean.parseBoolean(activo.toString()));
        if(dto.get("categoryId") != null) {
            categoryRepository.findById(Integer.parseInt(dto.get("categoryId").toString())).ifPresent(s::setCategory);
        }
        aplicarSlug(s);
        return ResponseEntity.ok(subcategoryRepository.save(s));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> dto) {
        return subcategoryRepository.findById(id).map(s -> {
            if(dto.containsKey("name")) s.setName((String) dto.get("name"));
            if(dto.containsKey("sortOrder") && dto.get("sortOrder") != null) s.setSortOrder(Integer.parseInt(dto.get("sortOrder").toString()));
            Object act = dto.containsKey("isActive") ? dto.get("isActive") : dto.get("active");
            if(act != null) s.setActive(Boolean.parseBoolean(act.toString()));
            if(dto.containsKey("categoryId") && dto.get("categoryId") != null) {
                categoryRepository.findById(Integer.parseInt(dto.get("categoryId").toString())).ifPresent(s::setCategory);
            }
            // Se recalcula solo si llegó un nombre nuevo, para no romper URLs
            // ya publicadas al guardar otros campos.
            if (dto.containsKey("name") || s.getSlug() == null || s.getSlug().isBlank()) {
                aplicarSlug(s);
            }
            return ResponseEntity.ok(subcategoryRepository.save(s));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * El slug se deriva del nombre y nunca se toma del cuerpo: el panel no lo
     * envía y la columna es NOT NULL UNIQUE, así que antes quedaba en null y
     * el insert fallaba con "Column 'slug' cannot be null".
     */
    private void aplicarSlug(Subcategory s) {
        String base = SlugUtils.makeOrDefault(s.getName(), "subcategoria");
        final Integer propio = s.getId();
        s.setSlug(SlugUtils.unique(base, candidato ->
                subcategoryRepository.findBySlug(candidato)
                        .filter(otra -> !otra.getId().equals(propio))
                        .isPresent()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        subcategoryRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
