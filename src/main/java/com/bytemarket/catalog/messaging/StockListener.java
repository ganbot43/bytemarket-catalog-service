package com.bytemarket.catalog.messaging;

import com.bytemarket.catalog.config.RabbitConfig;
import com.bytemarket.catalog.service.StockService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Aplica los movimientos de stock que llegan por la cola.
 *
 * Si catalog está apagado los mensajes se acumulan en RabbitMQ y se procesan
 * al arrancar. Delega en StockService, que es idempotente: un mismo pedido
 * repetido no descuenta dos veces.
 */
@Component
@RequiredArgsConstructor
public class StockListener {

    private static final Logger log = LoggerFactory.getLogger(StockListener.class);

    private final StockService stockService;

    @RabbitListener(queues = RabbitConfig.COLA_STOCK)
    @SuppressWarnings("unchecked")
    public void alRecibir(Map<String, Object> evento) {
        String tipo = String.valueOf(evento.get("tipo"));
        String codigo = String.valueOf(evento.get("orderCode"));
        Long orderId = evento.get("orderId") == null ? null : Long.valueOf(evento.get("orderId").toString());
        List<Map<String, Object>> items = (List<Map<String, Object>>) evento.get("items");

        try {
            int aplicadas = switch (tipo) {
                case "exit" -> stockService.descontar(orderId, items);
                case "entry" -> stockService.devolver(orderId, items);
                // Un tipo desconocido no se arregla reintentando: se aparta a
                // la cola de fallidos en vez de reencolarse sin fin.
                default -> throw new AmqpRejectAndDontRequeueException("Tipo de movimiento desconocido: " + tipo);
            };
            log.info("Pedido {}: aplicadas {} líneas de stock ({})", codigo, aplicadas, tipo);
        } catch (AmqpRejectAndDontRequeueException e) {
            throw e;
        } catch (Exception e) {
            // Fallo transitorio (la base no responde): se deja que reintente.
            log.error("Pedido {}: fallo aplicando stock, se reintentará: {}", codigo, e.getMessage());
            throw e;
        }
    }
}
