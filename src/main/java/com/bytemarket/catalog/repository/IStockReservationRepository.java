package com.bytemarket.catalog.repository;

import com.bytemarket.catalog.model.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface IStockReservationRepository extends JpaRepository<StockReservation, Integer> {

    /** Las de un pedido, en el estado que sea. */
    List<StockReservation> findByOrderCode(String orderCode);

    /** Las de un pedido que siguen apartadas. */
    List<StockReservation> findByOrderCodeAndStatus(String orderCode, String status);

    /**
     * Las que se pasaron de plazo y nadie confirmó ni liberó.
     *
     * Es la red de seguridad: si entre reservar y descontar se cae algo, sus
     * unidades vuelven solas en el siguiente barrido.
     */
    @Query("""
            SELECT r FROM StockReservation r
            WHERE r.status = com.bytemarket.catalog.model.StockReservation.RETENIDA
              AND r.expiresAt < :ahora
            ORDER BY r.expiresAt ASC
            """)
    List<StockReservation> caducadas(@Param("ahora") LocalDateTime ahora);

    /** Para el panel: cuántas unidades de un producto están comprometidas. */
    @Query("""
            SELECT COALESCE(SUM(r.quantity), 0) FROM StockReservation r
            WHERE r.product.id = :productId
              AND r.status = com.bytemarket.catalog.model.StockReservation.RETENIDA
            """)
    int retenidasDe(@Param("productId") Integer productId);
}
