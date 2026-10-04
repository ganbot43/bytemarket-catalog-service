package com.bytemarket.catalog.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "inventory_movements")
public class InventoryMovement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "movement_type", length = 20, nullable = false)
    private String movementType; // 'entry' | 'exit' | 'adjustment'

    @Column(nullable = false)
    private Integer quantity = 0;

    private Integer delta;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "related_order_id")
    private Long relatedOrderId;

    @Column(name = "created_by")
    private Long createdById;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
