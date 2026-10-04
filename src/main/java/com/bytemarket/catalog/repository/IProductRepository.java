package com.bytemarket.catalog.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.model.Category;

@Repository
public interface IProductRepository extends JpaRepository<Product, Integer>, JpaSpecificationExecutor<Product> {

    @EntityGraph(attributePaths = {"category", "images"})
    Optional<Product> findBySlug(String slug);

    @EntityGraph(attributePaths = {"category", "images"})
    @Query("SELECT p FROM Product p LEFT JOIN p.category c WHERE p.isActive = :isActive AND (c IS NULL OR c.isActive = 1)")
    Page<Product> findByIsActive(@Param("isActive") Integer isActive, Pageable pageable);

    // Ensure findAll with specification uses the entity graph
    @EntityGraph(attributePaths = {"category", "images"})
    Page<Product> findAll(org.springframework.data.jpa.domain.Specification<Product> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "images"})
    @Query("SELECT p FROM Product p LEFT JOIN p.category c WHERE p.isActive = :isActive AND (c IS NULL OR c.isActive = 1)")
    List<Product> findByIsActive(@Param("isActive") Integer isActive);

    @EntityGraph(attributePaths = {"category", "images"})
    @Query("SELECT p FROM Product p LEFT JOIN p.category c WHERE p.nuevoLanzamiento = :nuevoLanzamiento AND p.isActive = :isActive AND (c IS NULL OR c.isActive = 1)")
    List<Product> findByNuevoLanzamientoAndIsActive(@Param("nuevoLanzamiento") Integer nuevoLanzamiento, @Param("isActive") Integer isActive);

    @EntityGraph(attributePaths = {"category", "images"})
    @Query("SELECT p FROM Product p LEFT JOIN p.category c WHERE p.nuevoLanzamiento = :nuevoLanzamiento AND p.isActive = :isActive AND (c IS NULL OR c.isActive = 1)")
    Page<Product> findByNuevoLanzamientoAndIsActive(@Param("nuevoLanzamiento") Integer nuevoLanzamiento, @Param("isActive") Integer isActive, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "images"})
    @Query("SELECT p FROM Product p LEFT JOIN p.category c WHERE p.discountPrice IS NOT NULL AND p.isActive = :isActive AND (c IS NULL OR c.isActive = 1)")
    Page<Product> findByDiscountPriceIsNotNullAndIsActive(@Param("isActive") Integer isActive, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "images"})
    @Query("SELECT p FROM Product p LEFT JOIN p.category c WHERE p.isFeatured = :isFeatured AND p.isActive = :isActive AND (c IS NULL OR c.isActive = 1)")
    Page<Product> findByIsFeaturedAndIsActive(@Param("isFeatured") Integer isFeatured, @Param("isActive") Integer isActive, Pageable pageable);

    long countByCategoryAndIsActive(Category category, int isActive);

    /**
     * Totales reales del catálogo, para las tarjetas del panel.
     *
     * El panel los contaba sobre la página devuelta, así que con el límite
     * por defecto de 10 nunca mostraba más de 10 aunque hubiera más.
     */
    long countByIsActive(Integer isActive);

    List<Product> findByCategory(Category category);
    List<Product> findBySubcategoryId(Integer subcategoryId);

    @Query("SELECT p FROM Product p LEFT JOIN p.category c WHERE p.isActive = 1 AND (c IS NULL OR c.isActive = 1) ORDER BY p.id DESC")
    Page<Product> findBestSellers(Pageable pageable);

    @Query("SELECT p FROM Product p LEFT JOIN p.category c WHERE p.isActive = 1 AND (c IS NULL OR c.isActive = 1) ORDER BY p.id DESC")
    List<Product> findBestSellers();

    /**
     * Reporte: Productos con stock por debajo del umbral indicado.
     * Retorna: id, nombre, categoria, stock, precio, estado
     * Solo productos activos con track_stock activado.
     */
    @Query(value = """
            SELECT
                p.id                                AS id,
                p.name                              AS nombre,
                COALESCE(c.name, 'Sin categoría')   AS categoria,
                p.stock                             AS stock,
                p.price                             AS precio,
                p.status                            AS estado
            FROM products p
            LEFT JOIN categories c ON c.id = p.category_id
            WHERE p.is_active = 1
              AND p.track_stock = 1
              AND p.stock <= :umbral
            ORDER BY p.stock ASC, p.name ASC
            """, nativeQuery = true)
    List<Map<String, Object>> findProductosBajoStock(@Param("umbral") int umbral);
}
