package com.bytemarket.catalog.repository;

import com.bytemarket.catalog.model.InventoryMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IInventoryMovementRepository extends JpaRepository<InventoryMovement, Integer> {
    Page<InventoryMovement> findAll(Pageable pageable);
    Page<InventoryMovement> findByMovementType(String movementType, Pageable pageable);
    Page<InventoryMovement> findByProductId(Integer productId, Pageable pageable);
    Page<InventoryMovement> findByProductIdAndMovementType(Integer productId, String movementType, Pageable pageable);

    /**
     * Kardex filtrado, con el buscador por nombre de producto.
     *
     * Una sola consulta con los tres filtros opcionales en vez de un método
     * por combinación: con producto, tipo y texto eran ocho casos y la
     * cadena de ifs del controlador ya se había quedado corta al añadir el
     * buscador.
     */
    @Query("""
            SELECT m FROM InventoryMovement m
            WHERE (:productId IS NULL OR m.product.id = :productId)
              AND (:tipo IS NULL OR m.movementType = :tipo)
              AND (:patron IS NULL OR LOWER(m.product.name) LIKE :patron)
            """)
    Page<InventoryMovement> buscar(@Param("productId") Integer productId,
                                   @Param("tipo") String tipo,
                                   @Param("patron") String patron,
                                   Pageable pageable);

    /** Para el backfill: evita duplicar el movimiento de un pedido ya registrado. */
    boolean existsByRelatedOrderIdAndProductId(Long relatedOrderId, Integer productId);

    /** Idempotencia del descuento de stock: distingue la salida por venta de
        la entrada por cancelación del mismo pedido. */
    boolean existsByRelatedOrderIdAndProductIdAndMovementType(
            Long relatedOrderId, Integer productId, String movementType);

    /** Un producto con kardex no se borra: se desactiva, para no perder el historial. */
    boolean existsByProductId(Integer productId);
}
