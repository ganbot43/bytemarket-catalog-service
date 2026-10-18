package com.bytemarket.catalog.service;

import com.bytemarket.catalog.model.InventoryMovement;
import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.model.StockReservation;
import com.bytemarket.catalog.repository.IInventoryMovementRepository;
import com.bytemarket.catalog.repository.IProductRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Mueve el stock por una venta o su cancelación.
 *
 * Vive aquí, y no dentro del controlador, porque ahora tiene DOS entradas: la
 * llamada REST que hace order-service por Feign y el mensaje que llega por la
 * cola. Ambas tienen que comportarse igual.
 *
 * Es IDEMPOTENTE a propósito: RabbitMQ garantiza entrega "al menos una vez",
 * así que un mismo pedido puede llegar repetido si el consumidor se cae justo
 * después de procesarlo y antes de confirmar. Sin esta comprobación, ese
 * reintento descontaría el stock dos veces y el inventario quedaría mal sin
 * que nadie se entere. El movimiento de kardex ya guarda related_order_id, así
 * que sirve de marca de "esto ya se hizo".
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    /** Cuántas veces releer el stock antes de rendirse con un movimiento parcial. */
    private static final int REINTENTOS = 5;

    private final IProductRepository productRepository;
    private final IInventoryMovementRepository movementRepository;

    private final ReservationService reservationService;

    /**
     * Descuenta por una venta. Devuelve cuántas líneas aplicó.
     *
     * Si el pedido venía con unidades apartadas —el camino normal del
     * checkout— se consumen esas: bajan a la vez el stock y lo comprometido,
     * y el kardex anota la salida. Esa ruta no puede quedarse corta, porque
     * las unidades estaban guardadas desde antes de que el pedido existiera.
     *
     * Sin reserva se descuenta como siempre. Es lo que pasa con las ventas
     * de mostrador, que entregan en el acto, y con los pedidos anteriores a
     * que existieran las reservas.
     */
    @Transactional
    public int descontar(Long orderId, String orderCode, List<Map<String, Object>> items) {
        if (orderCode != null && !orderCode.isBlank()) {
            List<StockReservation> consumidas = reservationService.consumir(orderCode);
            if (!consumidas.isEmpty()) {
                for (StockReservation r : consumidas) {
                    InventoryMovement mov = new InventoryMovement();
                    mov.setProduct(r.getProduct());
                    mov.setMovementType("exit");
                    mov.setQuantity(r.getQuantity());
                    mov.setDelta(-r.getQuantity());
                    mov.setReason("Venta");
                    mov.setRelatedOrderId(orderId);
                    movementRepository.save(mov);
                }
                log.debug("Pedido {}: consumidas {} reserva(s)", orderCode, consumidas.size());
                return consumidas.size();
            }
        }
        return mover(orderId, items, "exit", "Venta", -1);
    }

    /** Sin código de pedido: no hay reserva que consumir. */
    @Transactional
    public int descontar(Long orderId, List<Map<String, Object>> items) {
        return descontar(orderId, null, items);
    }

    /** Devuelve el stock al cancelar un pedido. */
    @Transactional
    public int devolver(Long orderId, List<Map<String, Object>> items) {
        return mover(orderId, items, "entry", "Cancelación de pedido", 1);
    }

    private int mover(Long orderId, List<Map<String, Object>> items,
                      String tipo, String motivo, int signo) {
        if (items == null) return 0;

        int aplicadas = 0;
        for (Map<String, Object> item : items) {
            Integer productId = entero(item.get("productId"));
            int cantidad = item.get("quantity") == null ? 0 : Integer.parseInt(item.get("quantity").toString());
            if (productId == null || cantidad <= 0) continue;

            // La marca de "ya procesado". Se comprueba por producto y tipo:
            // un pedido puede tener una salida por venta y, más tarde, una
            // entrada por cancelación, y ambas son legítimas.
            if (orderId != null
                    && movementRepository.existsByRelatedOrderIdAndProductIdAndMovementType(orderId, productId, tipo)) {
                log.debug("Pedido {} producto {}: el movimiento '{}' ya estaba aplicado, se ignora",
                        orderId, productId, tipo);
                continue;
            }

            Product p = productRepository.findById(productId).orElse(null);
            // trackStock a 0 significa que ese producto no lleva inventario.
            if (p == null || p.getTrackStock() == null || p.getTrackStock() != 1) continue;

            int deltaPedido = signo * cantidad;

            /*
             * El movimiento y la existencia tienen que contar lo mismo.
             *
             * Antes esto era `setStock(Math.max(0, actual + delta))` seguido
             * de un save, y guardaba el movimiento con el delta COMPLETO.
             * Con stock 2 y una venta de 5, la columna pasaba a 0 —se había
             * movido 2— y el kardex anotaba -5. A partir de ahí el histórico
             * dejaba de explicar la existencia, y nada lo avisaba.
             *
             * aplicarMovimiento devuelve lo que SE MOVIÓ de verdad, releído
             * de la base y no de la copia que se cargó más arriba.
             */
            int deltaAplicado = aplicarMovimiento(productId, deltaPedido);

            if (deltaAplicado == 0) {
                // Ni una unidad. Se avisa y no se escribe movimiento: un
                // delta de cero no explica nada y ensucia el kardex.
                log.error("Pedido {} producto {}: no se pudo aplicar el movimiento '{}' de {} unidades; "
                                + "el stock no daba. Queda sin descontar.",
                        orderId, productId, tipo, cantidad);
                continue;
            }

            String razon = motivo;
            if (deltaAplicado != deltaPedido) {
                int faltaron = cantidad - Math.abs(deltaAplicado);
                razon = motivo + " (faltaron " + faltaron + ", stock insuficiente)";
                log.error("Pedido {} producto {}: se pidieron {} unidades y solo se pudieron mover {}. "
                                + "El kardex lo deja anotado.",
                        orderId, productId, cantidad, Math.abs(deltaAplicado));
            }

            InventoryMovement mov = new InventoryMovement();
            mov.setProduct(p);
            mov.setMovementType(tipo);
            mov.setQuantity(Math.abs(deltaAplicado));
            mov.setDelta(deltaAplicado);
            mov.setReason(razon);
            mov.setRelatedOrderId(orderId);
            movementRepository.save(mov);
            aplicadas++;
        }
        return aplicadas;
    }

    /**
     * Mueve lo que se pide; si no cabe entero, lo que quepa. Devuelve lo
     * que de verdad se movió, que es lo único que se puede anotar.
     *
     * La cifra para el movimiento parcial se RELEE, no se reutiliza la que
     * se cargó al principio del bucle: entre aquella lectura y este punto
     * cabe otra venta del mismo producto, y entonces el número viejo ya no
     * es el que hay. Reintentar es la forma barata de resolverlo sin
     * bloquear la fila: si otro se adelanta, se vuelve a mirar.
     *
     * Y se mira SIEMPRE el valor que devuelve el UPDATE: cero filas
     * significa que no se movió nada, por mucho que la lectura dijera otra
     * cosa un instante antes.
     */
    private int aplicarMovimiento(Integer productId, int delta) {
        if (delta == 0) return 0;
        if (productRepository.moverStock(productId, delta) > 0) return delta;

        // Una entrada no puede fallar por falta de sitio: si falló, el
        // producto ya no está, y no hay movimiento parcial que valga.
        if (delta > 0) return 0;

        for (int intento = 0; intento < REINTENTOS; intento++) {
            Integer disponible = productRepository.stockDe(productId);
            if (disponible == null || disponible <= 0) return 0;
            if (productRepository.moverStock(productId, -disponible) > 0) return -disponible;
        }
        log.error("Producto {}: el stock cambió en cada uno de los {} intentos; no se aplicó nada.",
                productId, REINTENTOS);
        return 0;
    }

    private static Integer entero(Object v) {
        return v == null ? null : Integer.valueOf(v.toString());
    }
}
