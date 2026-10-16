package com.bytemarket.catalog.repository;

import com.bytemarket.catalog.model.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de la capa de acceso a datos del repositorio de categorías.
 *
 * @DataJpaTest levanta solo JPA contra una base en memoria y revierte la
 * transacción al final de cada prueba, de modo que no se toca MySQL ni
 * importa el orden de ejecución.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("ICategoryRepository — capa de acceso a datos")
class ICategoryRepositoryTest {

    @Autowired
    private ICategoryRepository repositorio;

    @Autowired
    private TestEntityManager em;

    private Category nuevaCategoria(String nombre, String slug, int orden, int activa) {
        Category c = new Category();
        c.setName(nombre);
        c.setSlug(slug);
        c.setSortOrder(orden);
        c.setIsActive(activa);
        return c;
    }

    @BeforeEach
    void limpiar() {
        repositorio.deleteAll();
        em.flush();
    }

    // ── INSERTAR ─────────────────────────────────────────────────────

    @Test
    @DisplayName("insertar: persiste la categoría con id generado y fecha de creación")
    void insertar() {
        Category guardada = repositorio.save(nuevaCategoria("Pantallas y Displays", "pantallas-displays", 1, 1));
        em.flush();
        em.clear();

        assertNotNull(guardada.getId());

        Category desdeLaBase = em.find(Category.class, guardada.getId());
        assertNotNull(desdeLaBase);
        assertEquals("Pantallas y Displays", desdeLaBase.getName());
        assertEquals("pantallas-displays", desdeLaBase.getSlug());
        assertNotNull(desdeLaBase.getCreatedAt(), "@PrePersist debe fijar la fecha de creación");
    }

    @Test
    @DisplayName("insertar: el slug es único y un duplicado no se persiste")
    void insertarSlugDuplicadoFalla() {
        repositorio.save(nuevaCategoria("Baterías", "baterias", 1, 1));
        em.flush();

        assertThrows(DataIntegrityViolationException.class, () -> {
            repositorio.save(nuevaCategoria("Baterías y Cargadores", "baterias", 2, 1));
            em.flush();
        });
    }

    @Test
    @DisplayName("insertar: el banner admite una URL larga por ser columna de texto")
    void insertarConBanner() {
        Category c = nuevaCategoria("Carcasas", "carcasas", 3, 1);
        /* El campo se declara como TEXT justamente porque guarda claves de S3
           que superan con holgura los 255 caracteres de un VARCHAR corriente. */
        c.setImagenBanner("bytemarket/categories/2026/10/15/" + "x".repeat(400) + ".webp");

        Category guardada = repositorio.save(c);
        em.flush();
        em.clear();

        assertTrue(em.find(Category.class, guardada.getId()).getImagenBanner().length() > 400);
    }

    // ── ACTUALIZAR ───────────────────────────────────────────────────

    @Test
    @DisplayName("actualizar: modifica los campos sin crear una fila nueva")
    void actualizar() {
        Category original = repositorio.save(nuevaCategoria("Accesorios", "accesorios", 5, 1));
        em.flush();
        Integer id = original.getId();

        original.setName("Accesorios y Cables");
        original.setSortOrder(2);
        original.setIsActive(0);
        repositorio.save(original);
        em.flush();
        em.clear();

        Category actualizada = em.find(Category.class, id);
        assertEquals("Accesorios y Cables", actualizada.getName());
        assertEquals(2, actualizada.getSortOrder());
        assertEquals(0, actualizada.getIsActive());
        assertEquals(1, repositorio.count(), "actualizar no debe insertar una fila adicional");
    }

    // ── ELIMINAR ─────────────────────────────────────────────────────

    @Test
    @DisplayName("eliminar: borra la fila y deja de encontrarse por id ni por slug")
    void eliminar() {
        Category c = repositorio.save(nuevaCategoria("Temporal", "temporal", 9, 1));
        em.flush();
        Integer id = c.getId();

        repositorio.deleteById(id);
        em.flush();
        em.clear();

        assertFalse(repositorio.findById(id).isPresent());
        assertTrue(repositorio.findBySlug("temporal").isEmpty());
        assertEquals(0, repositorio.count());
    }

    // ── LISTAR ───────────────────────────────────────────────────────

    @Test
    @DisplayName("listar: devuelve todas las categorías persistidas")
    void listar() {
        repositorio.save(nuevaCategoria("Pantallas", "pantallas", 1, 1));
        repositorio.save(nuevaCategoria("Baterías", "baterias", 2, 1));
        repositorio.save(nuevaCategoria("Cables", "cables", 3, 0));
        em.flush();
        em.clear();

        assertEquals(3, repositorio.findAll().size());
    }

    @Test
    @DisplayName("listar: findBySlug recupera la categoría y distingue la inexistente")
    void listarPorSlug() {
        repositorio.save(nuevaCategoria("Pantallas y Displays", "pantallas-displays", 1, 1));
        em.flush();
        em.clear();

        Optional<Category> encontrada = repositorio.findBySlug("pantallas-displays");
        assertTrue(encontrada.isPresent());
        assertEquals("Pantallas y Displays", encontrada.get().getName());

        assertTrue(repositorio.findBySlug("no-existe").isEmpty());
    }

    @Test
    @DisplayName("listar: findByIsActiveOrderBySortOrderAsc filtra las activas y respeta el orden")
    void listarActivasOrdenadas() {
        repositorio.save(nuevaCategoria("Tercera", "tercera", 3, 1));
        repositorio.save(nuevaCategoria("Primera", "primera", 1, 1));
        repositorio.save(nuevaCategoria("Oculta", "oculta", 2, 0));
        em.flush();
        em.clear();

        List<Category> activas = repositorio.findByIsActiveOrderBySortOrderAsc(1);

        assertEquals(2, activas.size(), "la inactiva no debe aparecer");
        /* El orden importa: la portada pinta las categorías en esta secuencia,
           así que una consulta que lo ignore rompería la presentación. */
        assertEquals("Primera", activas.get(0).getName());
        assertEquals("Tercera", activas.get(1).getName());
    }
}
