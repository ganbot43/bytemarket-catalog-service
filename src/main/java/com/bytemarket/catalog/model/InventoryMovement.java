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

    /**
     * El nombre de quien hizo el movimiento, copiado al guardar y no
     * resuelto al leer.
     *
     * Es la misma decisión que en los turnos de caja (cashierName,
     * settledByName): un kardex tiene que seguir diciendo quién movió el
     * stock aunque después se renombre o se elimine a esa persona. Resolver
     * el id contra user-service al consultar daría un historial que cambia
     * con el tiempo, y en blanco si el servicio está caído.
     *
     * En los movimientos que entran por la cola queda nulo: no hay persona
     * —los escribe el consumidor de RabbitMQ, sin petición HTTP— y el DTO
     * deriva esa condición de que tampoco haya createdById. Guardar aquí un
     * centinela como "Sistema" sería falsificable: el nombre lo elige el
     * propio usuario y nadie lo valida.
     */
    @Column(name = "created_by_name", length = 120)
    private String createdByName;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
