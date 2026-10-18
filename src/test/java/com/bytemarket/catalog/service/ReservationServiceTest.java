package com.bytemarket.catalog.service;

import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.model.StockReservation;
import com.bytemarket.catalog.repository.IInventoryMovementRepository;
import com.bytemarket.catalog.repository.IProductRepository;
import com.bytemarket.catalog.repository.IStockReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El apartado de stock: que dos pedidos no puedan llevarse la misma unidad.
 *
 * Sin transacción de test a propósito. Las reservas se apoyan en UPDATE
 * atómicos y en lo que de verdad queda escrito; envolverlo todo en una
 * transacción que se revierte al final escondería justo lo que se quiere
 * comprobar. A cambio, cada prueba limpia lo suyo.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Reservas — apartar, consumir y devolver")
class ReservationServiceTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StockService stockService;

    @Autowired
    private IProductRepository productRepository;

    @Autowired
    private IStockReservationRepository reservationRepository;

    @Autowired
    private IInventoryMovementRepository movementRepository;

    private Product producto;

    @BeforeEach
    void unProductoConTresUnidades() {
        reservationRepository.deleteAll();
        movementRepository.deleteAll();

        producto = new Product();
        producto.setName("Batería de prueba");
        producto.setSlug("bateria-reserva-" + System.nanoTime());
        producto.setPrice(50.0);
        producto.setStock(3);
        producto.setReserved(0);
        producto.setTrackStock(1);
        producto.setIsActive(1);
        producto = productRepository.saveAndFlush(producto);
    }

    private List<Map<String, Object>> linea(int cantidad) {
        return List.of(Map.of("productId", producto.getId(), "quantity", cantidad));
    }

    @Test
    @DisplayName("apartar no mueve el stock, solo lo compromete")
    void reservarNoMueveStock() {
        reservationService.reservar("BM-RES-1", linea(2));

        assertEquals(3, productRepository.stockDe(producto.getId()), "la mercancía sigue en el almacén");
        assertEquals(2, productRepository.reservadoDe(producto.getId()), "pero dos ya tienen dueño");
        assertEquals(1, reservationService.disponibleDe(producto.getId()), "solo queda una que prometer");
    }

    @Test
    @DisplayName("dos pedidos no pueden llevarse la misma unidad")
    void noSePuedePrometerDosVeces() {
        // Este es EL caso. Antes los dos preguntaban "¿hay 2?", los dos
        // recibían que sí —porque ninguno había descontado todavía— y se
        // vendían cuatro unidades de tres.
        reservationService.reservar("BM-RES-A", linea(2));

        ReservationService.SinStockException e = assertThrows(
                ReservationService.SinStockException.class,
                () -> reservationService.reservar("BM-RES-B", linea(2)));

        assertTrue(e.getMessage().contains("1"), "debe decir cuántas quedan: " + e.getMessage());
        assertEquals(2, productRepository.reservadoDe(producto.getId()),
                "el segundo intento no debe dejar nada apartado");
    }

    @Test
    @DisplayName("si una línea no alcanza, se devuelve lo ya apartado del mismo pedido")
    void reservaParcialSeDeshace() {
        Product nuevo = new Product();
        nuevo.setName("Cable de prueba");
        nuevo.setSlug("cable-reserva-" + System.nanoTime());
        nuevo.setPrice(10.0);
        nuevo.setStock(1);
        nuevo.setReserved(0);
        nuevo.setTrackStock(1);
        nuevo.setIsActive(1);
        final Product otro = productRepository.saveAndFlush(nuevo);

        assertThrows(ReservationService.SinStockException.class,
                () -> reservationService.reservar("BM-RES-C", List.of(
                        Map.of("productId", producto.getId(), "quantity", 2),
                        Map.of("productId", otro.getId(), "quantity", 5))));

        // Media reserva es peor que ninguna: el cliente ve un error y aun
        // así tendría unidades bloqueadas a su nombre.
        assertEquals(0, productRepository.reservadoDe(producto.getId()),
                "lo apartado de la primera línea debe volver");

        productRepository.deleteById(otro.getId());
    }

    @Test
    @DisplayName("consumir baja el stock y lo comprometido a la vez, y escribe el kardex")
    void consumirMueveLasDosCifras() {
        reservationService.reservar("BM-RES-2", linea(2));
        int aplicadas = stockService.descontar(99L, "BM-RES-2", linea(2));

        // Cuenta LÍNEAS aplicadas, no unidades: una línea de dos unidades
        // es 1, igual que devuelve la ruta sin reserva.
        assertEquals(1, aplicadas);
        assertEquals(1, productRepository.stockDe(producto.getId()), "salieron dos del almacén");
        assertEquals(0, productRepository.reservadoDe(producto.getId()), "y dejaron de estar prometidas");

        int enElKardex = movementRepository.findAll().stream()
                .filter(m -> m.getProduct().getId().equals(producto.getId()))
                .mapToInt(m -> m.getDelta() == null ? 0 : m.getDelta())
                .sum();
        assertEquals(-2, enElKardex, "la salida tiene que quedar anotada");
    }

    @Test
    @DisplayName("consumir dos veces no descuenta dos veces")
    void consumirEsIdempotente() {
        reservationService.reservar("BM-RES-3", linea(2));
        stockService.descontar(98L, "BM-RES-3", linea(2));
        stockService.descontar(98L, "BM-RES-3", linea(2));

        assertEquals(1, productRepository.stockDe(producto.getId()),
                "la cola entrega 'al menos una vez'; repetir no puede cobrar dos");
    }

    @Test
    @DisplayName("liberar devuelve lo apartado sin tocar el stock")
    void liberarDevuelve() {
        reservationService.reservar("BM-RES-4", linea(3));
        assertEquals(0, reservationService.disponibleDe(producto.getId()));

        reservationService.liberar("BM-RES-4");

        assertEquals(3, productRepository.stockDe(producto.getId()), "nunca salió del almacén");
        assertEquals(0, productRepository.reservadoDe(producto.getId()));
        assertEquals(3, reservationService.disponibleDe(producto.getId()), "vuelve a poder prometerse");
    }

    @Test
    @DisplayName("una reserva caducada vuelve sola")
    void lasCaducadasVuelven() {
        reservationService.reservar("BM-RES-5", linea(3));

        // Se la envejece a mano: esperar quince minutos en un test no es
        // una opción, y lo que se prueba es el barrido, no el reloj.
        StockReservation r = reservationRepository.findByOrderCode("BM-RES-5").get(0);
        r.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        reservationRepository.saveAndFlush(r);

        reservationService.barrerCaducadas();

        assertEquals(0, productRepository.reservadoDe(producto.getId()),
                "sin esto, un fallo entre reservar y descontar dejaría el producto "
                        + "agotado para siempre con el almacén lleno");
        assertEquals(StockReservation.LIBERADA,
                reservationRepository.findById(r.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("una venta sin reserva se descuenta como siempre")
    void sinReservaSeDescuentaDirecto() {
        // Es el caso de la caja: la mercancía se entrega en el acto, no hay
        // nada que apartar. Y el de los pedidos anteriores a las reservas.
        int aplicadas = stockService.descontar(97L, "BM-SIN-RESERVA", linea(1));

        assertEquals(1, aplicadas);
        assertEquals(2, productRepository.stockDe(producto.getId()));
        assertEquals(0, productRepository.reservadoDe(producto.getId()));
    }
}
