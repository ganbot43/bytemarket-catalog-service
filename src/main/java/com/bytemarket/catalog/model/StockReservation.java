package com.bytemarket.catalog.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Unidades apartadas para un pedido concreto, con fecha de caducidad.
 *
 * `Product.reserved` dice CUÁNTAS hay comprometidas; esto dice de quién son
 * y hasta cuándo. Las dos cifras tienen que cuadrar, igual que el kardex
 * cuadra con la existencia.
 *
 * El plazo es lo que hace que el sistema se arregle solo. Entre apartar las
 * unidades y descontarlas puede pasar cualquier cosa —order-service se cae
 * al guardar, RabbitMQ no entrega, alguien desenchufa la máquina—, y sin
 * plazo esas unidades quedarían apartadas para siempre para un pedido que no
 * existe: el catálogo diría "agotado" con el almacén lleno. Con plazo, el
 * barrido las devuelve y nadie tiene que acordarse de nada.
 *
 * Se guarda también la reserva ya consumida o liberada, en vez de borrarla:
 * cuando alguien pregunte por qué no se pudo vender algo a las tres de la
 * tarde, la respuesta está aquí.
 */
@Data
@Entity
@Table(name = "stock_reservations", indexes = {
        // El barrido busca por estado y fecha; la confirmación, por pedido.
        @Index(name = "idx_reservas_estado_caduca", columnList = "status, expires_at"),
        @Index(name = "idx_reservas_pedido", columnList = "order_code"),
        @Index(name = "idx_reservas_token", columnList = "token")
})
public class StockReservation {

    /** Apartada y esperando: cuenta en `Product.reserved`. */
    public static final String RETENIDA = "held";
    /** Convertida en salida real: ya no cuenta. */
    public static final String CONSUMIDA = "claimed";
    /** Devuelta sin vender, por caducidad o por fallo del pedido. */
    public static final String LIBERADA = "released";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * La llave de esta reserva, única por construcción.
     *
     * No se usa el código del pedido como llave, aunque lo parezca: ese
     * código se genera ANTES de guardar el pedido, comprobando que no
     * exista ya en la tabla de pedidos. Dos checkouts a la vez pueden
     * sacar el mismo número, porque ninguno de los dos se ha guardado
     * todavía cuando el otro mira.
     *
     * La colisión es rara —cuatro dígitos al azar— pero lo que provoca no
     * lo es: liberar "el pedido BM-20261017-1234" devolvería también las
     * unidades del otro cliente, y consumirlo le descontaría el stock dos
     * veces. Un UUID no colisiona y eso deja de poder pasar.
     *
     * No lleva restricción de unicidad: identifica al GRUPO de reservas de
     * un pedido, una por línea del carrito, y las tres del mismo carrito
     * comparten token a propósito.
     */
    @Column(name = "token", length = 36, nullable = false)
    private String token;

    /**
     * El pedido que la pidió, por código y no por id.
     *
     * El código se genera antes de guardar el pedido, así que existe en el
     * momento de reservar; el id solo existe después. Reservar después de
     * guardar sería tarde: el pedido ya estaría en pie sin unidades que lo
     * respalden.
     */
    @Column(name = "order_code", length = 191, nullable = false)
    private String orderCode;

    @Column(nullable = false)
    private Integer quantity;

    @Column(length = 20, nullable = false)
    private String status = RETENIDA;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    void alCrear() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
