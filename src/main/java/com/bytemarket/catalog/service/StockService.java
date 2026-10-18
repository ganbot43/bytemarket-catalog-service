package com.bytemarket.catalog.service;

import com.bytemarket.catalog.model.InventoryMovement;
import com.bytemarket.catalog.model.Product;
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

    private final IProductRepository productRepository;
    private final IInventoryMovementRepository movementRepository;

    /** Descuenta por una venta. Devuelve cuántas líneas aplicó. */
    @Transactional
    public int descontar(Long orderId, List<Map<String, Object>> items) {
        return mover(orderId, items, "exit", "Venta", -1);
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

            int actual = p.getStock() == null ? 0 : p.getStock();
            int deltaPedido = signo * cantidad;
            String razon = motivo;

            /*
             * El movimiento y la existencia tienen que contar lo mismo.
             *
             * Antes esto era `setStock(Math.max(0, actual + delta))` seguido
             * de un save, y guardaba el movimiento con el delta COMPLETO.
             * Con stock 2 y una venta de 5, la columna pasaba a 0 —se había
             * movido 2— y el kardex anotaba -5. A partir de ahí el histórico
             * dejaba de explicar la existencia, y nada lo avisaba: el recorte
             * era invisible.
             *
             * Ahora la suma la hace la base de datos en un solo UPDATE, que
             * además es atómico y cierra la carrera del "lee, suma, escribe".
             * Si no cabe, se aplica lo que hay y el movimiento anota ESO,
             * con el motivo diciendo que faltaron unidades.
             */
            int deltaAplicado = deltaPedido;
            if (productRepository.moverStock(productId, deltaPedido) == 0) {
                // No cupo: el stock se quedaría en negativo. Pasa cuando dos
                // ventas se llevan la última unidad, porque validar y
                // descontar no son el mismo instante.
                deltaAplicado = -actual;
                int faltan = cantidad - actual;
                razon = motivo + " (faltaron " + faltan + ", stock insuficiente)";
                log.error("Pedido {} producto {}: se pidieron {} unidades y solo había {}. "
                                + "Se descuenta hasta cero y el kardex lo deja anotado.",
                        orderId, productId, cantidad, actual);
                if (deltaAplicado != 0) {
                    productRepository.moverStock(productId, deltaAplicado);
                }
            }

            // Un movimiento de cero no mueve nada: anotarlo solo ensucia el
            // kardex. El log de arriba ya dejó constancia del intento.
            if (deltaAplicado == 0) continue;

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

    private static Integer entero(Object v) {
        return v == null ? null : Integer.valueOf(v.toString());
    }
}
