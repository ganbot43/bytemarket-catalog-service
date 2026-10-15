package com.bytemarket.catalog.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "reviews", indexes = {
        // La ficha de producto pide siempre las aprobadas de UN producto, y
        // el panel filtra por estado. Sin estos índices MySQL recorre la
        // tabla entera en cada visita a una ficha: con dos reseñas da igual,
        // con miles la ficha se arrastra.
        @Index(name = "idx_reviews_producto_estado", columnList = "product_id, status"),
        @Index(name = "idx_reviews_estado_fecha", columnList = "status, created_at"),
        // existsByUserIdAndProductId, que impide la segunda reseña del mismo
        // cliente sobre el mismo producto.
        @Index(name = "idx_reviews_usuario_producto", columnList = "user_id, product_id")
})
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "product_id")
    private Integer productId;

    @Column(nullable = false)
    private Integer rating;

    @Column(columnDefinition = "TEXT")
    private String comment;

    // "pending", "approved", "rejected"
    @Column(length = 30, nullable = false)
    private String status = "pending";

    @Column(length = 255)
    private String imageUrl;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
