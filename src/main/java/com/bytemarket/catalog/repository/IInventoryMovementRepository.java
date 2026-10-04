package com.bytemarket.catalog.repository;

import com.bytemarket.catalog.model.InventoryMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IInventoryMovementRepository extends JpaRepository<InventoryMovement, Integer> {
    Page<InventoryMovement> findAll(Pageable pageable);
    Page<InventoryMovement> findByMovementType(String movementType, Pageable pageable);
    Page<InventoryMovement> findByProductId(Integer productId, Pageable pageable);
    Page<InventoryMovement> findByProductIdAndMovementType(Integer productId, String movementType, Pageable pageable);

    /** Para el backfill: evita duplicar el movimiento de un pedido ya registrado. */
    boolean existsByRelatedOrderIdAndProductId(Long relatedOrderId, Integer productId);

    /** Un producto con kardex no se borra: se desactiva, para no perder el historial. */
    boolean existsByProductId(Integer productId);
}
