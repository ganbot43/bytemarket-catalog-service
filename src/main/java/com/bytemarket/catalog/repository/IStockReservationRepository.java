package com.bytemarket.catalog.repository;

import com.bytemarket.catalog.model.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface IStockReservationRepository extends JpaRepository<StockReservation, Integer> {

    /** Las de una reserva, en el estado que sea. */
    List<StockReservation> findByToken(String token);

    /** Las de una reserva que siguen apartadas. */
    List<StockReservation> findByTokenAndStatus(String token, String status);

    /** Por código de pedido: solo para mirar, nunca para liberar ni consumir. */
    List<StockReservation> findByOrderCode(String orderCode);

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

    /**
     * Cambia el estado solo si sigue retenida, y dice si lo consiguió.
     *
     * Leer la reserva, mirar su estado y escribirlo son tres pasos, y entre
     * ellos cabe otro hilo haciendo lo mismo: el barrido de caducadas y una
     * liberación manual pueden coincidir sobre la misma fila, y entonces
     * las dos devolverían las unidades al producto. El `reserved` bajaría
     * el doble de lo que subió, y el catálogo diría que hay más disponible
     * del que hay.
     *
     * Con la condición dentro del UPDATE solo una de las dos se lleva la
     * fila; la otra recibe 0 y no toca los contadores.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StockReservation r SET r.status = :nuevo, r.resolvedAt = :ahora
            WHERE r.id = :id AND r.status = com.bytemarket.catalog.model.StockReservation.RETENIDA
            """)
    int resolver(@Param("id") Integer id,
                 @Param("nuevo") String nuevo,
                 @Param("ahora") LocalDateTime ahora);

    /** Para el panel: cuántas unidades de un producto están comprometidas. */
    @Query("""
            SELECT COALESCE(SUM(r.quantity), 0) FROM StockReservation r
            WHERE r.product.id = :productId
              AND r.status = com.bytemarket.catalog.model.StockReservation.RETENIDA
            """)
    int retenidasDe(@Param("productId") Integer productId);
}
