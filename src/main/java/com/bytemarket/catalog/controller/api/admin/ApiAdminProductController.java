package com.bytemarket.catalog.controller.api.admin;

import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.model.Category;
import com.bytemarket.catalog.model.ProductImage;
import com.bytemarket.catalog.repository.IProductRepository;
import com.bytemarket.catalog.repository.ICategoryRepository;
import com.bytemarket.catalog.repository.IProductImageRepository;
import com.bytemarket.catalog.repository.IInventoryMovementRepository;
import com.bytemarket.catalog.service.SlugUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import com.bytemarket.catalog.repository.ProductoSpecification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/admin/products")
public class ApiAdminProductController {

    @Autowired
    private IProductRepository productRepository;
    
    @Autowired
    private ICategoryRepository categoryRepository;

    @Autowired
    private IProductImageRepository productImageRepository;

    @Autowired
    private com.bytemarket.catalog.service.ProductPresenter productPresenter;

    @Autowired
    private com.bytemarket.catalog.service.S3Service s3Service;

    @Autowired
    private IInventoryMovementRepository inventoryMovementRepository;

    /**
     * Listado del panel.
     *
     * Reutiliza ProductoSpecification, la misma que usa la tienda, pero con
     * isActive = null salvo que se pida: el panel tiene que ver también los
     * productos desactivados, que es justo lo que la tienda esconde.
     */
    @GetMapping
    public Map<String, Object> getAll(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) Integer isActive) {

        // limit 0 reventaba con division by zero al calcular la página.
        int tamano = Math.max(1, limit);

        Specification<Product> filtros = ProductoSpecification.conFiltros(
                null, null, categoryId, null, q, null, null, isActive, null, null);

        Page<Product> page = productRepository.findAll(filtros, PageRequest.of(offset / tamano, tamano));
        List<Map<String, Object>> data = page.getContent().stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("slug", p.getSlug());
            m.put("price", p.getPrice());
            m.put("comparePrice", p.getDiscountPrice());
            m.put("stock", p.getStock());
            m.put("categoryId", p.getCategory() != null ? p.getCategory().getId() : null);
            m.put("subcategoryId", p.getSubcategoryId());
            m.put("trackStock", p.getTrackStock() != null && p.getTrackStock() == 1);
            m.put("isFeatured", p.getIsFeatured() != null && p.getIsFeatured() == 1);
            m.put("nuevoLanzamiento", p.getNuevoLanzamiento() != null && p.getNuevoLanzamiento() == 1);
            m.put("isActive", p.getIsActive() != null && p.getIsActive() == 1);
            // La descripción faltaba y el formulario de edición la cargaba
            // vacía: al guardar, escribía "" y BORRABA la descripción real.
            m.put("description", p.getDescription());
            m.put("createdAt", p.getCreatedAt());
            // La tabla pinta category.name bajo el nombre del producto.
            m.put("category", p.getCategory() == null ? null
                    : Map.of("id", p.getCategory().getId(), "name", p.getCategory().getName()));
            // Firmadas: el panel también las muestra y el bucket es privado.
            m.put("images", productPresenter.imagenes(p));
            return m;
        }).collect(Collectors.toList());
        
        Map<String, Object> resp = new HashMap<>();
        resp.put("data", data);
        resp.put("total", page.getTotalElements());
        // Totales de todo el catálogo, no de esta página: las tarjetas del
        // panel los contaban sobre `data` y con limit=10 se quedaban en 10.
        resp.put("totalActive", productRepository.countByIsActive(1));
        resp.put("totalInactive", productRepository.countByIsActive(0));
        return resp;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> dto) {
        // Sin nombre no se puede derivar el slug, y en base es obligatorio.
        String nombre = texto(dto.get("name"));
        if (nombre == null || nombre.length() < 2) {
            return error("El nombre del producto es obligatorio");
        }
        if (dto.get("price") == null) {
            return error("El precio del producto es obligatorio");
        }

        Product p = new Product();
        updateProductFromDto(p, dto);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        Product saved = productRepository.save(p);
        saveImages(saved, dto);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> dto) {
        return productRepository.findById(id).map(p -> {
            updateProductFromDto(p, dto);
            p.setUpdatedAt(LocalDateTime.now());
            Product saved = productRepository.save(p);
            saveImages(saved, dto);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Product.images no tiene cascada, así que la clave ajena de
     * product_images impedía borrar cualquier producto con fotos (es decir,
     * todos): fallaba con un "Datos inválidos" que no decía nada. Las
     * imágenes se borran aquí porque pertenecen al producto.
     *
     * El kardex es otra cosa: si el producto tiene movimientos de inventario,
     * borrarlo destruiría el historial, así que se pide desactivarlo.
     */
    @DeleteMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        Product p = productRepository.findById(id).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();

        if (inventoryMovementRepository.existsByProductId(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "statusCode", 409,
                    "message", "Este producto tiene movimientos de inventario. "
                             + "Desactívalo en vez de eliminarlo para no perder el historial."));
        }

        if (p.getImages() != null && !p.getImages().isEmpty()) {
            productImageRepository.deleteAll(p.getImages());
        }
        productRepository.delete(p);
        return ResponseEntity.noContent().build();
    }

    private void updateProductFromDto(Product p, Map<String, Object> dto) {
        if(dto.containsKey("name")) p.setName((String) dto.get("name"));
        // El slug se deriva del nombre y no se acepta del cliente: el panel no
        // lo envía y la columna es NOT NULL UNIQUE. Al editar solo se recalcula
        // si cambió el nombre, para no romper URLs ya publicadas.
        if (p.getSlug() == null || p.getSlug().isBlank()
                || (dto.containsKey("name") && !SlugUtils.make(p.getName()).equals(baseDe(p.getSlug())))) {
            String base = SlugUtils.makeOrDefault(p.getName(), "producto");
            final Integer propio = p.getId();
            p.setSlug(SlugUtils.unique(base, s ->
                    productRepository.findBySlug(s)
                            .filter(otro -> !otro.getId().equals(propio))
                            .isPresent()));
        }
        if(dto.containsKey("description")) p.setDescription((String) dto.get("description"));
        if(dto.containsKey("price") && dto.get("price") != null) p.setPrice(Double.parseDouble(dto.get("price").toString()));
        if(dto.containsKey("comparePrice") && dto.get("comparePrice") != null) p.setDiscountPrice(Double.parseDouble(dto.get("comparePrice").toString()));
        else if (dto.containsKey("comparePrice") && dto.get("comparePrice") == null) p.setDiscountPrice(null);
        if(dto.containsKey("stock") && dto.get("stock") != null) p.setStock(Integer.parseInt(dto.get("stock").toString()));
        if(dto.containsKey("categoryId") && dto.get("categoryId") != null) {
            categoryRepository.findById(Integer.parseInt(dto.get("categoryId").toString())).ifPresent(p::setCategory);
        }
        if(dto.containsKey("subcategoryId") && dto.get("subcategoryId") != null) p.setSubcategoryId(Integer.parseInt(dto.get("subcategoryId").toString()));
        if(dto.containsKey("trackStock") && dto.get("trackStock") != null) p.setTrackStock(Boolean.parseBoolean(dto.get("trackStock").toString()) ? 1 : 0);
        if(dto.containsKey("isFeatured") && dto.get("isFeatured") != null) p.setIsFeatured(Boolean.parseBoolean(dto.get("isFeatured").toString()) ? 1 : 0);
        if(dto.containsKey("nuevoLanzamiento") && dto.get("nuevoLanzamiento") != null) p.setNuevoLanzamiento(Boolean.parseBoolean(dto.get("nuevoLanzamiento").toString()) ? 1 : 0);
        if(dto.containsKey("isActive") && dto.get("isActive") != null) p.setIsActive(Boolean.parseBoolean(dto.get("isActive").toString()) ? 1 : 0);
    }
    
    /** Quita el sufijo "-2", "-3"… que pone SlugUtils.unique al desempatar. */
    private static String baseDe(String slug) {
        return slug == null ? null : slug.replaceAll("-\\d+$", "");
    }

    private static String texto(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private ResponseEntity<Map<String, Object>> error(String mensaje) {
        return ResponseEntity.badRequest().body(Map.of("statusCode", 400, "message", mensaje));
    }

    private void saveImages(Product p, Map<String, Object> dto) {
        if(dto.containsKey("images")) {
            List<Map<String, Object>> images = (List<Map<String, Object>>) dto.get("images");
            if (p.getImages() != null && !p.getImages().isEmpty()) {
                productImageRepository.deleteAll(p.getImages());
            }
            int sortOrder = 0;
            for (Map<String, Object> imgDto : images) {
                ProductImage img = new ProductImage();
                img.setProduct(p);
                // Sin firma: el panel reenvía la URL firmada que recibió al leer.
                img.setUrl(s3Service.normalizeUrl((String) imgDto.get("url")));
                img.setSortOrder(sortOrder++);
                img.setIsPrimary((Boolean) imgDto.get("isPrimary") ? 1 : 0);
                productImageRepository.save(img);
            }
        }
    }
}
