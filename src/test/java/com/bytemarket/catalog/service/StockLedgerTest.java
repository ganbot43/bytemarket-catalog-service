package com.bytemarket.catalog.service;

import com.bytemarket.catalog.model.InventoryMovement;
import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.repository.IInventoryMovementRepository;
import com.bytemarket.catalog.repository.IProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La invariante del inventario: el kardex explica la existencia.
 *
 * Sumar los `delta` de los movimientos de un producto tiene que dar su
 * stock. Si no da, el histórico deja de servir para auditar: dice que
 * salieron cinco unidades donde la columna dice que salieron dos, y no hay
 * forma de saber cuál de las dos miente.
 *
 * Se rompía con un `Math.max(0, ...)` que recortaba la existencia a cero
 * pero guardaba el movimiento con la cantidad pedida entera. El recorte era
 * invisible: ni excepción, ni aviso, ni rastro.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Inventario — el kardex cuadra con la existencia")
class StockLedgerTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private IProductRepository productRepository;

    @Autowired
    private IInventoryMovementRepository movementRepository;

    private Product producto;

    @BeforeEach
    void unProductoConDosUnidades() {
        producto = new Product();
        producto.setName("Pantalla de prueba");
        producto.setSlug("pantalla-de-prueba-ledger");
        producto.setPrice(100.0);
        producto.setStock(2);
        producto.setTrackStock(1);
        producto.setIsActive(1);
        producto = productRepository.saveAndFlush(producto);
    }

    private Product conStock(int stock) {
        producto.setStock(stock);
        return producto;
    }

    private List<InventoryMovement> movimientosDelProducto() {
        return movementRepository.findAll().stream()
                .filter(m -> m.getProduct() != null && m.getProduct().getId().equals(producto.getId()))
                .toList();
    }

    /** Lo que dice el kardex que debería haber. */
    private int segunElKardex() {
        return movementRepository.findAll().stream()
                .filter(m -> m.getProduct() != null && m.getProduct().getId().equals(producto.getId()))
                .mapToInt(m -> m.getDelta() == null ? 0 : m.getDelta())
                .sum();
    }

    /** Lo que dice la columna. */
    private int segunLaColumna() {
        return productRepository.stockDe(producto.getId());
    }

    @Test
    @DisplayName("una venta normal deja las dos cuentas iguales")
    void ventaNormalCuadra() {
        stockService.descontar(1L, List.of(Map.of("productId", producto.getId(), "quantity", 2)));

        assertEquals(0, segunLaColumna());
        assertEquals(-2, segunElKardex(), "dos unidades menos, y el kardex lo dice");
        assertEquals(2 + segunElKardex(), segunLaColumna(), "la existencia es lo inicial más los movimientos");
    }

    @Test
    @DisplayName("vender más de lo que hay no descuadra el kardex")
    void venderDeMasNoDescuadra() {
        // Pasa de verdad: validar el stock y descontarlo no son el mismo
        // instante, así que dos ventas pueden llevarse la última unidad.
        stockService.descontar(2L, List.of(Map.of("productId", producto.getId(), "quantity", 5)));

        assertEquals(0, segunLaColumna(), "no se puede bajar de cero");

        // Lo que importa: el movimiento anota lo que SE MOVIÓ (-2), no lo
        // que se pidió (-5). Antes anotaba -5 y las dos cuentas quedaban
        // separadas para siempre.
        assertEquals(-2, segunElKardex(),
                "el kardex debe anotar lo que de verdad salió, no lo que se pidió");
        assertEquals(2 + segunElKardex(), segunLaColumna());

        // Y el descuadre entre lo pedido y lo habido queda escrito, que es
        // lo que permite investigarlo después.
        InventoryMovement mov = movementRepository.findAll().stream()
                .filter(m -> m.getProduct().getId().equals(producto.getId()))
                .findFirst().orElseThrow();
        assertTrue(mov.getReason() != null && mov.getReason().contains("faltaron"),
                "el motivo debe dejar constancia de que no alcanzaba: " + mov.getReason());
    }

    @Test
    @DisplayName("sin stock no se escribe movimiento: un delta de cero no explica nada")
    void sinStockNoHayMovimiento() {
        productRepository.saveAndFlush(conStock(0));

        stockService.descontar(4L, List.of(Map.of("productId", producto.getId(), "quantity", 3)));

        assertEquals(0, segunLaColumna());
        assertEquals(0, movimientosDelProducto().size(),
                "no se movió nada, así que no hay nada que anotar");
    }

    @Test
    @DisplayName("el movimiento de una venta no se atribuye a ninguna persona")
    void laVentaNoTieneAutorHumano() {
        stockService.descontar(5L, List.of(Map.of("productId", producto.getId(), "quantity", 1)));

        InventoryMovement mov = movimientosDelProducto().stream().findFirst().orElseThrow();

        // Lo escribe el consumidor de RabbitMQ: no hay petición HTTP ni JWT
        // del que sacar un autor, así que las dos columnas quedan nulas y de
        // ahí deduce el panel que fue automático.
        assertNull(mov.getCreatedById(), "nadie hizo esto a mano");

        // Y no se rellena con un centinela tipo "Sistema": el nombre lo
        // elige cada usuario sin validación —ApiProfileController solo hace
        // trim()—, así que un admin renombrado podría hacer pasar un ajuste
        // manual por uno automático en la pantalla de auditoría.
        assertNull(mov.getCreatedByName(),
                "la columna del nombre es para personas; el caso automático se deduce del id");
    }

    @Test
    @DisplayName("la devolución por cancelación también cuadra")
    void cancelacionCuadra() {
        stockService.descontar(3L, List.of(Map.of("productId", producto.getId(), "quantity", 2)));
        stockService.devolver(3L, List.of(Map.of("productId", producto.getId(), "quantity", 2)));

        assertEquals(2, segunLaColumna(), "vuelve a donde estaba");
        assertEquals(0, segunElKardex(), "una salida y una entrada se anulan");
    }
}
