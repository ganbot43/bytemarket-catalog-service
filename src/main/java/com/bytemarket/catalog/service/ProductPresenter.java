package com.bytemarket.catalog.service;

import com.bytemarket.catalog.model.Category;
import com.bytemarket.catalog.model.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Arma el producto tal como lo espera el frontend, firmando las URLs de las
 * imágenes.
 *
 * Existe para que el catálogo y la portada devuelvan exactamente la misma
 * forma: la portada entregaba las entidades crudas y el catálogo un mapa
 * distinto, así que la misma tarjeta recibía campos diferentes según de
 * dónde viniera.
 */
@Component
@RequiredArgsConstructor
public class ProductPresenter {

    private final S3Service s3Service;

    public List<Map<String, Object>> toList(List<Product> productos) {
        if (productos == null) return List.of();
        return productos.stream().map(this::toMap).toList();
    }

    public Map<String, Object> toMap(Product p) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", p.getId());
        map.put("name", p.getName());
        map.put("slug", p.getSlug());
        map.put("description", p.getDescription());
        map.put("price", p.getPrice());
        map.put("discountPrice", p.getDiscountPrice());
        // El frontend (useProductDisplay) lee comparePrice para pintar el
        // precio tachado; en la entidad el campo se llama discountPrice.
        map.put("comparePrice", p.getDiscountPrice());
        map.put("stock", p.getStock());
        map.put("marca", p.getMarca());
        map.put("condicion", p.getCondicion());
        map.put("garantia", p.getGarantia());
        map.put("status", p.getStatus());
        map.put("trackStock", p.getTrackStock() != null && p.getTrackStock() == 1);
        map.put("isFeatured", p.getIsFeatured() != null && p.getIsFeatured() == 1);
        map.put("nuevoLanzamiento", p.getNuevoLanzamiento() != null && p.getNuevoLanzamiento() == 1);
        map.put("isActive", p.getIsActive() != null && p.getIsActive() == 1);
        map.put("categoryId", p.getCategory() != null ? p.getCategory().getId() : null);
        map.put("subcategoryId", p.getSubcategoryId());
        map.put("images", imagenes(p));
        map.put("category", categoria(p.getCategory()));
        map.put("createdAt", p.getCreatedAt());
        map.put("updatedAt", p.getUpdatedAt());
        return map;
    }

    /** Las URLs se firman aquí, al leer: el bucket es privado. */
    public List<Map<String, Object>> imagenes(Product p) {
        if (p.getImages() == null) return List.of();
        return p.getImages().stream().map(img -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", img.getId());
            m.put("url", s3Service.getPresignedUrl(img.getUrl()));
            m.put("isPrimary", img.getIsPrimary() != null && img.getIsPrimary() == 1);
            m.put("sortOrder", img.getSortOrder());
            return m;
        }).toList();
    }

    public Map<String, Object> categoria(Category c) {
        if (c == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("slug", c.getSlug());
        m.put("imagenBanner", s3Service.getPresignedUrl(c.getImagenBanner()));
        return m;
    }
}
