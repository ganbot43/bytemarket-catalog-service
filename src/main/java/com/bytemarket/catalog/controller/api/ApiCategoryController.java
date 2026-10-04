package com.bytemarket.catalog.controller.api;

import com.bytemarket.catalog.model.Category;
import com.bytemarket.catalog.model.Subcategory;
import com.bytemarket.catalog.repository.ICategoryRepository;
import com.bytemarket.catalog.repository.ISubcategoryRepository;
import com.bytemarket.catalog.repository.IProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/categories")
public class ApiCategoryController {

    @Autowired
    private ICategoryRepository categoryRepository;

    @Autowired
    private ISubcategoryRepository subcategoryRepository;

    @Autowired
    private IProductRepository productRepository;

    @Autowired
    private com.bytemarket.catalog.service.S3Service s3Service;

    @GetMapping
    public Map<String, Object> getCategories(@RequestParam(value = "active", required = false) Boolean active) {
        List<Category> categories = categoryRepository.findAll();
        if (Boolean.TRUE.equals(active)) {
            categories = categoryRepository.findByIsActiveOrderBySortOrderAsc(1);
        }

        List<Subcategory> allSubcategories = subcategoryRepository.findAll();

        List<Map<String, Object>> data = categories.stream().map(c -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("name", c.getName());
            map.put("slug", c.getSlug());
            map.put("sortOrder", c.getSortOrder());
            // Booleano, como el resto de la API: en base es 0/1.
            map.put("isActive", c.getIsActive() != null && c.getIsActive() == 1);
            map.put("seccion", c.getSeccion());
            map.put("idProducto", c.getIdProducto());
            // El bucket es privado: la URL se firma aquí, al leer.
            map.put("imagenBanner", s3Service.getPresignedUrl(c.getImagenBanner()));
            map.put("productCount", productRepository.countByCategoryAndIsActive(c, 1));
            
            List<Map<String, Object>> subs = allSubcategories.stream()
                .filter(s -> s.getCategory() != null && s.getCategory().getId().equals(c.getId()))
                .map(s -> {
                    Map<String, Object> smap = new HashMap<>();
                    smap.put("id", s.getId());
                    smap.put("name", s.getName());
                    smap.put("slug", s.getSlug());
                    smap.put("categoryId", s.getCategory().getId());
                    smap.put("sortOrder", s.getSortOrder());
                    smap.put("active", s.isActive());
                    return smap;
                })
                .collect(Collectors.toList());
            
            map.put("subcategories", subs);
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("data", data);
        return response;
    }
}
