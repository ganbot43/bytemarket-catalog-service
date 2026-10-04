package com.bytemarket.catalog.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "products", indexes = {
    @Index(name = "idx_product_active", columnList = "is_active"),
    @Index(name = "idx_product_featured", columnList = "is_featured"),
    @Index(name = "idx_product_nuevo", columnList = "nuevo_lanzamiento"),
    @Index(name = "idx_product_category", columnList = "category_id")
})
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "subcategory_id")
    private Integer subcategoryId;

    @Column(name = "seller_id")
    private Long sellerId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(length = 191, nullable = false, unique = true)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String marca = "ByteMarket";

    @Column(length = 50)
    private String condicion = "Nuevo";

    @Column(length = 100)
    private String garantia = "12 meses";

    @Column(nullable = false)
    private Double price;

    @Column(name = "discount_price")
    private Double discountPrice;

    @Column(nullable = false)
    private Integer stock = 0;

    @Column(name = "track_stock", nullable = false)
    private Integer trackStock = 1;

    @Column(name = "is_featured", nullable = false)
    private Integer isFeatured = 0;

    @Column(name = "nuevo_lanzamiento", nullable = false)
    private Integer nuevoLanzamiento = 0;

    @Column(name = "is_active", nullable = false)
    private Integer isActive = 1;

    @Column(length = 30)
    private String status = "disponible";

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
    private List<ProductImage> images;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
