package com.bytemarket.catalog.repository;

import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.model.Category;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public class ProductoSpecification {

    public static Specification<Product> conFiltros(
            Integer nuevoLanzamiento,
            Integer isFeatured,
            Integer categoryId,
            String categorySlug,
            String queryBusqueda,
            Double precioMin,
            Double precioMax,
            Integer isActive,
            Integer enOferta,
            List<Integer> ids
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicados = new ArrayList<>();

            // Si se especifican IDs puntuales
            if (ids != null && !ids.isEmpty()) {
                predicados.add(root.get("id").in(ids));
            }

            // Producto activo por defecto
            if (isActive != null) {
                predicados.add(criteriaBuilder.equal(root.get("isActive"), isActive));
                if (isActive == 1) {
                    Join<Product, Category> joinCategoria = root.join("category", JoinType.LEFT);
                    Predicate categoryIsNull = criteriaBuilder.isNull(joinCategoria);
                    Predicate categoryIsActive = criteriaBuilder.equal(joinCategoria.get("isActive"), 1);
                    predicados.add(criteriaBuilder.or(categoryIsNull, categoryIsActive));
                }
            }

            if (nuevoLanzamiento != null && nuevoLanzamiento == 1) {
                predicados.add(criteriaBuilder.equal(root.get("nuevoLanzamiento"), 1));
            }

            if (isFeatured != null && isFeatured == 1) {
                predicados.add(criteriaBuilder.equal(root.get("isFeatured"), 1));
            }

            if (enOferta != null && enOferta == 1) {
                predicados.add(criteriaBuilder.isNotNull(root.get("discountPrice")));
            }

            if (precioMin != null) {
                predicados.add(criteriaBuilder.greaterThanOrEqualTo(root.get("price"), precioMin));
            }

            if (precioMax != null) {
                predicados.add(criteriaBuilder.lessThanOrEqualTo(root.get("price"), precioMax));
            }

            if (categoryId != null || (categorySlug != null && !categorySlug.trim().isEmpty())) {
                Join<Product, Category> joinCategoria = root.join("category", JoinType.LEFT);
                if (categoryId != null) {
                    predicados.add(criteriaBuilder.equal(joinCategoria.get("id"), categoryId));
                }
                if (categorySlug != null && !categorySlug.trim().isEmpty()) {
                    predicados.add(criteriaBuilder.equal(joinCategoria.get("slug"), categorySlug));
                }
            }

            if (queryBusqueda != null && !queryBusqueda.trim().isEmpty()) {
                String likePattern = "%" + queryBusqueda.toLowerCase() + "%";
                Predicate coincidenciaNombre = criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), likePattern);
                Predicate coincidenciaDescripcion = criteriaBuilder.like(criteriaBuilder.lower(root.get("description")), likePattern);
                predicados.add(criteriaBuilder.or(coincidenciaNombre, coincidenciaDescripcion));
            }

            return criteriaBuilder.and(predicados.toArray(new Predicate[0]));
        };
    }
}
