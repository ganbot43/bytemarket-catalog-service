package com.bytemarket.catalog.repository;

import com.bytemarket.catalog.model.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public interface IReviewRepository extends JpaRepository<Review, Integer> {

    @Query("SELECT r FROM Review r WHERE r.productId = :productId AND r.status = 'approved' ORDER BY r.createdAt DESC")
    List<Review> findApprovedByProductId(@Param("productId") Integer productId);

    @Query(value = "SELECT r FROM Review r", countQuery = "SELECT COUNT(r) FROM Review r")
    Page<Review> findAllWithDetails(Pageable pageable);

    @Query("SELECT COALESCE(AVG(r.rating), 0.0) FROM Review r WHERE r.productId = :productId AND r.status = 'approved'")
    Double getAverageRatingForProduct(@Param("productId") Integer productId);

    @Query(value = """
            SELECT 
                p.id AS productId,
                p.name AS productName,
                p.slug AS productSlug,
                AVG(r.rating) AS averageRating,
                COUNT(r.id) AS totalReviews
            FROM reviews r
            JOIN products p ON r.product_id = p.id
            WHERE r.status = 'approved' AND p.is_active = 1
            GROUP BY p.id, p.name, p.slug
            ORDER BY averageRating DESC, totalReviews DESC
            LIMIT 5
            """, nativeQuery = true)
    List<Map<String, Object>> findTopRankedProducts();

    // userId es Long en la entidad: con Integer el tipo de la columna no
    // casaba y la consulta derivada fallaba.
    boolean existsByUserIdAndProductId(Long userId, Integer productId);

    Page<Review> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    Page<Review> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Listado del panel con estado y texto opcionales.
     *
     * productIds nunca llega vacío: el controlador manda un id imposible
     * cuando ningún producto coincide, porque "IN ()" no es SQL válido.
     */
    @Query("""
            SELECT r FROM Review r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:patron IS NULL
                   OR LOWER(COALESCE(r.comment, '')) LIKE :patron
                   OR r.productId IN :productIds)
            ORDER BY r.createdAt DESC
            """)
    Page<Review> buscar(@Param("status") String status,
                        @Param("patron") String patron,
                        @Param("productIds") java.util.List<Integer> productIds,
                        Pageable pageable);

    @Query("SELECT COUNT(r) FROM Review r WHERE r.productId = :productId AND r.status = 'approved'")
    long countApprovedByProductId(@Param("productId") Integer productId);
}
