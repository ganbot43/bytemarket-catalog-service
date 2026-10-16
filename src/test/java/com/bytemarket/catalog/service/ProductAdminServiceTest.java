package com.bytemarket.catalog.service;

import com.bytemarket.catalog.exception.CatalogoException;
import com.bytemarket.catalog.model.Category;
import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.model.ProductImage;
import com.bytemarket.catalog.repository.ICategoryRepository;
import com.bytemarket.catalog.repository.IInventoryMovementRepository;
import com.bytemarket.catalog.repository.IProductImageRepository;
import com.bytemarket.catalog.repository.IProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Reglas del mantenimiento de productos.
 *
 * Estos tests no existían, y no por descuido: la lógica vivía dentro de
 * ApiAdminProductController con cuatro repositorios inyectados, así que
 * probar la derivación del slug exigía levantar Spring entero con MockMvc.
 * Con ProductAdminService aparte, basta pasarle los colaboradores mockeados.
 */
class ProductAdminServiceTest {

    private IProductRepository productRepository;
    private ICategoryRepository categoryRepository;
    private IProductImageRepository productImageRepository;
    private IInventoryMovementRepository inventoryMovementRepository;
    private S3Service s3Service;
    private ProductAdminService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(IProductRepository.class);
        categoryRepository = mock(ICategoryRepository.class);
        productImageRepository = mock(IProductImageRepository.class);
        inventoryMovementRepository = mock(IInventoryMovementRepository.class);
        s3Service = mock(S3Service.class);

        service = new ProductAdminService(productRepository, categoryRepository,
                productImageRepository, inventoryMovementRepository, s3Service);

        // El save devuelve lo que se le pasa, como haría la base.
        when(productRepository.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));
        // Por defecto ningún slug está tomado.
        when(productRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(s3Service.normalizeUrl(anyString())).thenAnswer(i -> i.getArgument(0));
    }

    private Map<String, Object> dto(Object... pares) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pares.length; i += 2) m.put((String) pares[i], pares[i + 1]);
        return m;
    }

    // ─── Alta ───────────────────────────────────────────────────

    @Test
    @DisplayName("el slug se deriva del nombre, no se acepta del cliente")
    void derivaElSlug() {
        Product creado = service.crear(dto(
                "name", "Teclado Mecánico RGB", "price", 199.9,
                "slug", "lo-que-el-cliente-quiera"));

        assertEquals("teclado-mecanico-rgb", creado.getSlug());
    }

    @Test
    @DisplayName("si el slug ya existe se desempata con sufijo")
    void desempataElSlug() {
        Product otro = new Product();
        otro.setId(99);
        when(productRepository.findBySlug("mouse-gamer")).thenReturn(Optional.of(otro));

        Product creado = service.crear(dto("name", "Mouse Gamer", "price", 89.0));

        assertNotEquals("mouse-gamer", creado.getSlug());
        assertTrue(creado.getSlug().startsWith("mouse-gamer-"), creado.getSlug());
    }

    @Test
    @DisplayName("sin nombre no se crea: el slug sale de ahí y en base es obligatorio")
    void exigeNombre() {
        CatalogoException e = assertThrows(CatalogoException.class,
                () -> service.crear(dto("price", 10.0)));

        assertEquals(400, e.getStatus());
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("sin precio no se crea")
    void exigePrecio() {
        CatalogoException e = assertThrows(CatalogoException.class,
                () -> service.crear(dto("name", "Monitor 24")));

        assertEquals(400, e.getStatus());
    }

    @Test
    @DisplayName("un precio que no es número da 400, no un 500")
    void precioNoNumericoEs400() {
        // Antes esto salía como NumberFormatException y el panel mostraba
        // "Error inesperado" sin decir qué campo estaba mal.
        CatalogoException e = assertThrows(CatalogoException.class,
                () -> service.crear(dto("name", "Laptop", "price", "abc")));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().toLowerCase().contains("precio"), e.getMessage());
    }

    @Test
    @DisplayName("una categoría inexistente da 400 en vez de guardarse sin categoría")
    void categoriaInexistenteEs400() {
        when(categoryRepository.findById(77)).thenReturn(Optional.empty());

        CatalogoException e = assertThrows(CatalogoException.class,
                () -> service.crear(dto("name", "Case ATX", "price", 150.0, "categoryId", 77)));

        assertEquals(400, e.getStatus());
    }

    // ─── Edición ────────────────────────────────────────────────

    @Test
    @DisplayName("editar sin tocar el nombre NO recalcula el slug: hay URLs publicadas")
    void noRecalculaElSlugSiNoCambiaElNombre() {
        Product existente = new Product();
        existente.setId(5);
        existente.setName("Audífonos Pro");
        existente.setSlug("audifonos-pro");
        when(productRepository.findById(5)).thenReturn(Optional.of(existente));

        Product actualizado = service.actualizar(5, dto("price", 299.0));

        assertEquals("audifonos-pro", actualizado.getSlug());
    }

    @Test
    @DisplayName("cambiar el nombre sí recalcula el slug")
    void recalculaElSlugAlCambiarElNombre() {
        Product existente = new Product();
        existente.setId(5);
        existente.setName("Audífonos Pro");
        existente.setSlug("audifonos-pro");
        when(productRepository.findById(5)).thenReturn(Optional.of(existente));

        Product actualizado = service.actualizar(5, dto("name", "Audífonos Pro Max"));

        assertEquals("audifonos-pro-max", actualizado.getSlug());
    }

    @Test
    @DisplayName("el slug con sufijo no se considera un nombre cambiado")
    void elSufijoNoCuentaComoCambio() {
        // "mouse-gamer-2" viene de un desempate anterior. Si se tomara como
        // nombre distinto, cada guardado añadiría otro sufijo: -2, -2-2, …
        Product existente = new Product();
        existente.setId(8);
        existente.setName("Mouse Gamer");
        existente.setSlug("mouse-gamer-2");
        when(productRepository.findById(8)).thenReturn(Optional.of(existente));

        Product actualizado = service.actualizar(8, dto("name", "Mouse Gamer"));

        assertEquals("mouse-gamer-2", actualizado.getSlug());
    }

    @Test
    @DisplayName("un PUT parcial no borra los campos que no mandó")
    void elPutParcialNoBorra() {
        Product existente = new Product();
        existente.setId(5);
        existente.setName("Webcam HD");
        existente.setSlug("webcam-hd");
        existente.setDescription("Descripción buena");
        existente.setPrice(120.0);
        when(productRepository.findById(5)).thenReturn(Optional.of(existente));

        Product actualizado = service.actualizar(5, dto("price", 99.0));

        assertEquals("Descripción buena", actualizado.getDescription());
        assertEquals(99.0, actualizado.getPrice());
    }

    @Test
    @DisplayName("editar un producto que no existe da 404")
    void editarInexistenteEs404() {
        when(productRepository.findById(404)).thenReturn(Optional.empty());

        CatalogoException e = assertThrows(CatalogoException.class,
                () -> service.actualizar(404, dto("name", "Nada")));

        assertEquals(404, e.getStatus());
    }

    // ─── Imágenes ───────────────────────────────────────────────

    @Test
    @DisplayName("las imágenes se reemplazan en bloque y en el orden recibido")
    void reemplazaLasImagenes() {
        Product existente = new Product();
        existente.setId(5);
        existente.setName("Monitor");
        existente.setSlug("monitor");
        ProductImage vieja = new ProductImage();
        existente.setImages(new ArrayList<>(List.of(vieja)));
        when(productRepository.findById(5)).thenReturn(Optional.of(existente));

        service.actualizar(5, dto("images", List.of(
                Map.of("url", "https://b.s3.amazonaws.com/a.jpg?firma=1", "isPrimary", true),
                Map.of("url", "https://b.s3.amazonaws.com/b.jpg", "isPrimary", false))));

        verify(productImageRepository).deleteAll(List.of(vieja));

        ArgumentCaptor<ProductImage> guardadas = ArgumentCaptor.forClass(ProductImage.class);
        verify(productImageRepository, times(2)).save(guardadas.capture());

        List<ProductImage> todas = guardadas.getAllValues();
        assertEquals(0, todas.get(0).getSortOrder());
        assertEquals(1, todas.get(1).getSortOrder());
        assertEquals(1, todas.get(0).getIsPrimary());
        assertEquals(0, todas.get(1).getIsPrimary());
        // La firma se quita antes de guardar: en base va la URL estable.
        verify(s3Service).normalizeUrl("https://b.s3.amazonaws.com/a.jpg?firma=1");
    }

    @Test
    @DisplayName("un 'images' que no es lista da 400 en vez de un ClassCastException")
    void imagesMalFormadoEs400() {
        Product existente = new Product();
        existente.setId(5);
        existente.setName("Monitor");
        existente.setSlug("monitor");
        when(productRepository.findById(5)).thenReturn(Optional.of(existente));

        CatalogoException e = assertThrows(CatalogoException.class,
                () -> service.actualizar(5, dto("images", "no-soy-una-lista")));

        assertEquals(400, e.getStatus());
    }

    // ─── Baja ───────────────────────────────────────────────────

    @Test
    @DisplayName("un producto con kardex NO se borra: se pide desactivarlo")
    void noBorraSiTieneKardex() {
        Product existente = new Product();
        existente.setId(5);
        when(productRepository.findById(5)).thenReturn(Optional.of(existente));
        when(inventoryMovementRepository.existsByProductId(5)).thenReturn(true);

        CatalogoException e = assertThrows(CatalogoException.class, () -> service.eliminar(5));

        assertEquals(409, e.getStatus());
        verify(productRepository, never()).delete(any(Product.class));
    }

    @Test
    @DisplayName("borrar un producto borra primero sus imágenes: la FK lo impedía")
    void borraLasImagenesAntes() {
        Product existente = new Product();
        existente.setId(5);
        ProductImage img = new ProductImage();
        existente.setImages(new ArrayList<>(List.of(img)));
        when(productRepository.findById(5)).thenReturn(Optional.of(existente));
        when(inventoryMovementRepository.existsByProductId(5)).thenReturn(false);

        service.eliminar(5);

        verify(productImageRepository).deleteAll(List.of(img));
        verify(productRepository).delete(existente);
    }

    @Test
    @DisplayName("borrar un producto que no existe da 404")
    void borrarInexistenteEs404() {
        when(productRepository.findById(404)).thenReturn(Optional.empty());

        CatalogoException e = assertThrows(CatalogoException.class, () -> service.eliminar(404));

        assertEquals(404, e.getStatus());
    }

    // ─── Listado ────────────────────────────────────────────────

    @Test
    @DisplayName("limit 0 no revienta con division by zero")
    void limitCeroNoRevienta() {
        when(productRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertDoesNotThrow(() -> service.listar(0, 0, null, null, null));
    }

    @Test
    @DisplayName("los totales se cuentan contra la base, no sobre la página")
    void losTotalesSonDelCatalogoEntero() {
        when(productRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());
        when(productRepository.countByIsActive(1)).thenReturn(143L);
        when(productRepository.countByIsActive(0)).thenReturn(12L);

        ProductAdminService.PaginaDeProductos r = service.listar(10, 0, null, null, null);

        assertEquals(143L, r.totalActivos());
        assertEquals(12L, r.totalInactivos());
    }
}
