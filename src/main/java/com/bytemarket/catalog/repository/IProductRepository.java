package com.bytemarket.catalog.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Mueve el stock en una sola operación de base de datos.
     *
     * Leer el stock, sumarle y guardarlo son tres pasos, y entre el primero
     * y el tercero cabe otra venta: las dos leen 2, las dos escriben 1, y se
     * ha vendido una unidad que no existía. Es la condición de carrera
     * clásica del inventario, y no la arregla ninguna validación previa
     * porque el hueco está DESPUÉS de validar.
     *
     * Aquí la suma la hace MySQL dentro del UPDATE, que es atómico: dos
     * peticiones simultáneas se serializan solas.
     *
     * La condición `stock + :delta >= 0` es la otra mitad: si el movimiento
     * dejaría el stock en negativo, no se aplica y devuelve 0 filas. Quien
     * llama decide qué hacer con eso, pero se entera.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.stock = p.stock + :delta WHERE p.id = :id AND p.stock + :delta >= 0")
    int moverStock(@Param("id") Integer id, @Param("delta") int delta);

    /** Lo que de verdad hay ahora, releído tras un UPDATE atómico. */
    @Query("SELECT COALESCE(p.stock, 0) FROM Product p WHERE p.id = :id")
    Integer stockDe(@Param("id") Integer id);

    @EntityGraph(attributePaths = {"category", "images"})
    Optional<Product> findBySlug(String slug);

    /** Ids cuyo nombre coincide, para cruzar con tablas que guardan el
        product_id suelto (Review no tiene relación con Product). */
    @Query("SELECT p.id FROM Product p WHERE LOWER(p.name) LIKE :patron")
    List<Integer> findIdsByNombreLike(@Param("patron") String patron);

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
     * Existencias para la pantalla de inventario.
     *
     * Distinta de findProductosBajoStock, que devuelve TODO lo que está bajo
     * el umbral sin paginar porque alimenta una alerta. Esta es la lista que
     * se recorre a mano: pagina, busca por nombre y deja ver todo o solo lo
     * que falta, y ordena por el stock más bajo primero —que es el orden en
     * que se decide qué reponer—.
     *
     * Incluye los productos inactivos a propósito: un producto desactivado
     * sigue teniendo existencias físicas en el almacén.
     */
    @Query(value = """
            SELECT p.id                       AS id,
                   p.name                     AS name,
                   p.slug                     AS slug,
                   p.stock                    AS stock,
                   p.price                    AS price,
                   p.is_active                AS isActive,
                   p.track_stock               AS trackStock,
                   COALESCE(c.name, '')       AS category
            FROM products p
            LEFT JOIN categories c ON c.id = p.category_id
            WHERE p.track_stock = 1
              AND (:patron IS NULL OR LOWER(p.name) LIKE :patron)
              AND (:soloBajos = 0 OR p.stock <= :umbral)
            ORDER BY p.stock ASC, p.name ASC
            """,
            countQuery = """
            SELECT COUNT(*) FROM products p
            WHERE p.track_stock = 1
              AND (:patron IS NULL OR LOWER(p.name) LIKE :patron)
              AND (:soloBajos = 0 OR p.stock <= :umbral)
            """,
            nativeQuery = true)
    Page<Map<String, Object>> listarExistencias(@Param("patron") String patron,
                                                @Param("soloBajos") int soloBajos,
                                                @Param("umbral") int umbral,
                                                Pageable pageable);

    /** Cuántos productos están bajo el umbral y cuántos agotados del todo. */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN p.stock <= :umbral THEN 1 ELSE 0 END), 0) AS bajos,
                   COALESCE(SUM(CASE WHEN p.stock <= 0 THEN 1 ELSE 0 END), 0)       AS agotados,
                   COUNT(*)                                                         AS total
            FROM products p
            WHERE p.track_stock = 1
            """, nativeQuery = true)
    Map<String, Object> resumenDeExistencias(@Param("umbral") int umbral);

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
