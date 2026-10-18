package com.bytemarket.catalog.service;

import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.model.StockReservation;
import com.bytemarket.catalog.repository.IProductRepository;
import com.bytemarket.catalog.repository.IStockReservationRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Apartar unidades mientras se cierra un pedido, y devolverlas si no se
 * cierra.
 *
 * El problema que resuelve: validar el stock y descontarlo no ocurren en el
 * mismo instante. El checkout preguntaba "¿hay tres?", le decían que sí, y
 * el descuento llegaba después por la cola. Entre una cosa y otra cabía otra
 * compra que preguntaba lo mismo y recibía la misma respuesta, mirando una
 * cifra que ya estaba comprometida. Dos clientes, una unidad.
 *
 * El apartado cierra ese hueco porque la pregunta y la respuesta son la
 * misma operación: no se consulta el stock, se intenta coger. Si vuelve
 * vacío, no había.
 *
 * Tres estados y nada más:
 *
 *   reservar  →  las unidades quedan prometidas, el stock no se mueve
 *   consumir  →  salen de verdad: baja el stock y baja lo prometido
 *   liberar   →  vuelven a estar libres; el stock nunca se movió
 *
 * Y un plazo, que es lo que hace que esto no necesite vigilancia. Si entre
 * reservar y consumir se cae algo, las unidades volverían a estar apartadas
 * para siempre para un pedido que no existe: el catálogo diría "agotado" con
 * el almacén lleno. El barrido de abajo las devuelve solo.
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    /**
     * Cuánto se mantiene una reserva sin confirmar.
     *
     * Quince minutos no es el tiempo que tarda un checkout —eso son
     * segundos—, es el margen para que un servicio caído se levante y la
     * cola entregue lo pendiente. Más corto devolvería unidades de pedidos
     * que iban a confirmarse; más largo dejaría agotado un producto que no
     * lo está.
     */
    public static final int MINUTOS_DE_GRACIA = 15;

    private final IProductRepository productRepository;
    private final IStockReservationRepository reservationRepository;

    /** Lo que impide seguir: qué producto y cuánto quedaba. */
    public static class SinStockException extends RuntimeException {
        public SinStockException(String mensaje) {
            super(mensaje);
        }
    }

    /**
     * Aparta todo lo del pedido, o nada.
     *
     * Si una línea no alcanza, se devuelven las que ya se habían apartado.
     * Media reserva es peor que ninguna: el cliente vería un error y aun así
     * tendría unidades bloqueadas a su nombre durante el plazo de gracia.
     */
    @Transactional
    public void reservar(String orderCode, List<Map<String, Object>> items) {
        if (orderCode == null || orderCode.isBlank()) {
            throw new IllegalArgumentException("La reserva necesita el código del pedido");
        }
        if (items == null || items.isEmpty()) return;

        LocalDateTime caduca = LocalDateTime.now().plusMinutes(MINUTOS_DE_GRACIA);
        List<StockReservation> hechas = new ArrayList<>();

        for (Map<String, Object> item : items) {
            Integer productId = entero(item.get("productId"));
            int cantidad = entero(item.get("quantity")) == null ? 0 : entero(item.get("quantity"));
            if (productId == null || cantidad <= 0) continue;

            Product p = productRepository.findById(productId).orElse(null);
            if (p == null) {
                deshacer(hechas);
                throw new SinStockException("Uno de los productos ya no está disponible");
            }
            // trackStock a 0 significa que ese producto no lleva inventario:
            // no hay nada que apartar y tampoco nada que pueda faltar.
            if (p.getTrackStock() == null || p.getTrackStock() != 1) continue;

            if (productRepository.reservar(productId, cantidad) == 0) {
                int disponible = disponibleDe(productId);
                deshacer(hechas);
                throw new SinStockException(
                        disponible <= 0
                                ? "\"" + p.getName() + "\" se agotó mientras comprabas"
                                : "Solo quedan " + disponible + " unidades de \"" + p.getName() + "\"");
            }

            StockReservation r = new StockReservation();
            r.setProduct(p);
            r.setOrderCode(orderCode);
            r.setQuantity(cantidad);
            r.setStatus(StockReservation.RETENIDA);
            r.setExpiresAt(caduca);
            hechas.add(reservationRepository.save(r));
        }
        if (!hechas.isEmpty()) {
            log.debug("Pedido {}: {} línea(s) apartadas hasta {}", orderCode, hechas.size(), caduca);
        }
    }

    /**
     * Convierte en salida real lo apartado para un pedido.
     *
     * Devuelve las líneas consumidas, para que quien llame escriba el
     * kardex. Es idempotente: repetirlo no encuentra nada en estado
     * retenido y no hace nada, que es lo que necesita una cola que entrega
     * "al menos una vez".
     */
    @Transactional
    public List<StockReservation> consumir(String orderCode) {
        List<StockReservation> retenidas = reservationRepository
                .findByOrderCodeAndStatus(orderCode, StockReservation.RETENIDA);
        List<StockReservation> consumidas = new ArrayList<>();

        for (StockReservation r : retenidas) {
            Integer productId = r.getProduct().getId();
            if (productRepository.consumirReserva(productId, r.getQuantity()) == 0) {
                // No debería pasar: lo apartado está apartado. Si pasa, es
                // que alguien tocó el stock por fuera, y hay que saberlo.
                log.error("Pedido {} producto {}: no se pudo consumir la reserva de {} unidades. "
                                + "Alguien movió el stock por fuera.",
                        orderCode, productId, r.getQuantity());
                continue;
            }
            r.setStatus(StockReservation.CONSUMIDA);
            r.setResolvedAt(LocalDateTime.now());
            reservationRepository.save(r);
            consumidas.add(r);
        }
        return consumidas;
    }

    /** Devuelve lo apartado de un pedido que no salió adelante. */
    @Transactional
    public int liberar(String orderCode) {
        List<StockReservation> retenidas = reservationRepository
                .findByOrderCodeAndStatus(orderCode, StockReservation.RETENIDA);
        devolver(retenidas, "pedido no confirmado");
        return retenidas.size();
    }

    /**
     * Devuelve lo que se quedó apartado y nadie reclamó.
     *
     * Cada minuto, porque el coste es una consulta por índice sobre una
     * tabla pequeña y el precio de no hacerlo es un producto que figura
     * agotado sin estarlo.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void barrerCaducadas() {
        List<StockReservation> caducadas = reservationRepository.caducadas(LocalDateTime.now());
        if (caducadas.isEmpty()) return;
        devolver(caducadas, "caducada");
        log.warn("Devueltas {} reserva(s) caducadas. Si esto sale a menudo, algo está "
                + "reservando y no confirmando.", caducadas.size());
    }

    private void devolver(List<StockReservation> reservas, String motivo) {
        for (StockReservation r : reservas) {
            productRepository.liberarReserva(r.getProduct().getId(), r.getQuantity());
            r.setStatus(StockReservation.LIBERADA);
            r.setResolvedAt(LocalDateTime.now());
            reservationRepository.save(r);
            log.debug("Pedido {}: devueltas {} unidades del producto {} ({})",
                    r.getOrderCode(), r.getQuantity(), r.getProduct().getId(), motivo);
        }
    }

    private void deshacer(List<StockReservation> hechas) {
        devolver(hechas, "la reserva no se pudo completar entera");
    }

    /** Lo que de verdad se puede prometer: lo que hay menos lo comprometido. */
    public int disponibleDe(Integer productId) {
        Integer stock = productRepository.stockDe(productId);
        Integer reservado = productRepository.reservadoDe(productId);
        return Math.max(0, (stock == null ? 0 : stock) - (reservado == null ? 0 : reservado));
    }

    private static Integer entero(Object v) {
        return v == null ? null : Integer.valueOf(v.toString());
    }
}
