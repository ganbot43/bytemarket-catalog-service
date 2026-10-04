package com.bytemarket.catalog.service;

import com.bytemarket.catalog.model.Category;
import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.model.ProductImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Forma del producto que recibe el frontend.
 *
 * Existe porque la portada devolvía entidades crudas y el catálogo un mapa
 * distinto: la misma tarjeta recibía campos diferentes según de dónde
 * viniera, y el precio tachado no aparecía nunca.
 */
class ProductPresenterTest {

    private S3Service s3Service;
    private ProductPresenter presenter;

    @BeforeEach
    void setUp() {
        s3Service = mock(S3Service.class);
        // Simula el firmado: añade un marcador reconocible.
        when(s3Service.getPresignedUrl(anyString())).thenAnswer(i -> i.getArgument(0) + "?firmada");
        when(s3Service.getPresignedUrl(null)).thenReturn(null);
        presenter = new ProductPresenter(s3Service);
    }

    private Product producto() {
        Category c = new Category();
        c.setId(3);
        c.setName("Pantallas y Displays");
        c.setSlug("pantallas-y-displays");

        Product p = new Product();
        p.setId(1);
        p.setName("Pantalla OLED Samsung S22");
        p.setSlug("pantalla-oled-samsung-s22");
        p.setDescription("Repuesto original con garantía");
        p.setPrice(199.90);
        p.setDiscountPrice(149.90);
        p.setStock(12);
        p.setTrackStock(1);
        p.setIsActive(1);
        p.setIsFeatured(1);
        p.setNuevoLanzamiento(0);
        p.setCategory(c);
        return p;
    }

    @Test
    @DisplayName("expone comparePrice: el frontend lo lee para el precio tachado")
    void exponeComparePrice() {
        Map<String, Object> m = presenter.toMap(producto());

        assertEquals(149.90, (Double) m.get("comparePrice"), 0.001);
        assertEquals(149.90, (Double) m.get("discountPrice"), 0.001,
                "se conserva el nombre original para no romper a quien ya lo use");
        assertEquals(199.90, (Double) m.get("price"), 0.001);
    }

    @Test
    @DisplayName("los flags 0/1 de la base salen como booleanos")
    void flagsComoBooleanos() {
        Map<String, Object> m = presenter.toMap(producto());

        assertEquals(Boolean.TRUE, m.get("isActive"));
        assertEquals(Boolean.TRUE, m.get("isFeatured"));
        assertEquals(Boolean.FALSE, m.get("nuevoLanzamiento"));
        assertEquals(Boolean.TRUE, m.get("trackStock"));
    }

    @Test
    @DisplayName("la categoría va anidada con su nombre: la tabla lee category.name")
    void categoriaAnidada() {
        @SuppressWarnings("unchecked")
        Map<String, Object> cat = (Map<String, Object>) presenter.toMap(producto()).get("category");

        assertNotNull(cat);
        assertEquals("Pantallas y Displays", cat.get("name"));
        assertEquals(3, cat.get("id"));
    }

    @Test
    @DisplayName("un producto sin categoría no rompe")
    void sinCategoria() {
        Product p = producto();
        p.setCategory(null);

        Map<String, Object> m = presenter.toMap(p);
        assertNull(m.get("category"));
        assertNull(m.get("categoryId"));
    }

    @Test
    @DisplayName("las imágenes salen firmadas y con isPrimary booleano")
    void imagenesFirmadas() {
        Product p = producto();
        ProductImage principal = new ProductImage();
        principal.setId(10);
        principal.setUrl("https://bucket.s3.amazonaws.com/bytemarket/a.png");
        principal.setIsPrimary(1);
        principal.setSortOrder(0);
        ProductImage otra = new ProductImage();
        otra.setId(11);
        otra.setUrl("https://bucket.s3.amazonaws.com/bytemarket/b.png");
        otra.setIsPrimary(0);
        otra.setSortOrder(1);
        p.setImages(List.of(principal, otra));

        List<Map<String, Object>> imgs = presenter.imagenes(p);

        assertEquals(2, imgs.size());
        assertTrue(String.valueOf(imgs.get(0).get("url")).endsWith("?firmada"),
                "la URL debe venir firmada: el bucket es privado");
        assertEquals(Boolean.TRUE, imgs.get(0).get("isPrimary"));
        assertEquals(Boolean.FALSE, imgs.get(1).get("isPrimary"));
        verify(s3Service, times(2)).getPresignedUrl(anyString());
    }

    @Test
    @DisplayName("un producto sin imágenes devuelve lista vacía, no null")
    void sinImagenes() {
        Product p = producto();
        p.setImages(null);

        assertTrue(presenter.imagenes(p).isEmpty());
        assertTrue(((List<?>) presenter.toMap(p).get("images")).isEmpty());
    }

    @Test
    @DisplayName("toList mapea todos los productos")
    void listaCompleta() {
        assertEquals(2, presenter.toList(List.of(producto(), producto())).size());
        assertTrue(presenter.toList(null).isEmpty());
    }
}
