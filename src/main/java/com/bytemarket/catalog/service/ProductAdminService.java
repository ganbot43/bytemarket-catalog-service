package com.bytemarket.catalog.service;

import com.bytemarket.catalog.exception.CatalogoException;
import com.bytemarket.catalog.model.Category;
import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.model.ProductImage;
import com.bytemarket.catalog.repository.ICategoryRepository;
import com.bytemarket.catalog.repository.IInventoryMovementRepository;
import com.bytemarket.catalog.repository.IProductImageRepository;
import com.bytemarket.catalog.repository.IProductRepository;
import com.bytemarket.catalog.repository.ProductoSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Las reglas del mantenimiento de productos del panel.
 *
 * Estaban dentro de ApiAdminProductController, que inyectaba cuatro
 * repositorios y mezclaba en 225 líneas el manejo de HTTP, el mapeo a JSON y
 * decisiones que son de negocio y no de la web:
 *
 *   · cómo se deriva el slug y cuándo se recalcula sin romper URLs vivas,
 *   · que un producto con movimientos de kardex no se borra, se desactiva,
 *   · que las imágenes se reemplazan en bloque y pertenecen al producto.
 *
 * Ninguna de esas tres cosas depende de que la petición venga por HTTP, y
 * mientras vivían en el controlador no había forma de probarlas sin MockMvc
 * —que es exactamente por lo que no existía ni un test de productos—.
 *
 * Aquí tampoco se sabe nada de códigos de estado: los errores salen como
 * CatalogoException y es el GlobalExceptionHandler quien los traduce.
 */
@Service
@RequiredArgsConstructor
public class ProductAdminService {

    private final IProductRepository productRepository;
    private final ICategoryRepository categoryRepository;
    private final IProductImageRepository productImageRepository;
    private final IInventoryMovementRepository inventoryMovementRepository;
    private final S3Service s3Service;

    /** Una página del listado del panel con los totales de todo el catálogo. */
    public record PaginaDeProductos(Page<Product> pagina, long totalActivos, long totalInactivos) {}

    // ─── Listado ────────────────────────────────────────────────

    /**
     * Reutiliza ProductoSpecification, la misma que usa la tienda, pero con
     * isActive = null salvo que se pida: el panel tiene que ver también los
     * productos desactivados, que es justo lo que la tienda esconde.
     *
     * Los totales se cuentan contra la base y no sobre la página: las
     * tarjetas del panel los contaban sobre los resultados devueltos y con
     * limit=10 se quedaban siempre en 10.
     */
    public PaginaDeProductos listar(int limit, int offset, String q, Integer categoryId, Integer isActive) {
        // limit 0 reventaba con division by zero al calcular la página.
        int tamano = Math.max(1, limit);

        Specification<Product> filtros = ProductoSpecification.conFiltros(
                null, null, categoryId, null, q, null, null, isActive, null, null);

        Page<Product> pagina = productRepository.findAll(filtros, PageRequest.of(offset / tamano, tamano));
        return new PaginaDeProductos(
                pagina,
                productRepository.countByIsActive(1),
                productRepository.countByIsActive(0));
    }

    // ─── Alta, cambio y baja ────────────────────────────────────

    @Transactional
    public Product crear(Map<String, Object> dto) {
        // Sin nombre no se puede derivar el slug, y en base es obligatorio.
        String nombre = texto(dto.get("name"));
        if (nombre == null || nombre.length() < 2) {
            throw CatalogoException.invalida("El nombre del producto es obligatorio");
        }
        if (dto.get("price") == null) {
            throw CatalogoException.invalida("El precio del producto es obligatorio");
        }

        Product p = new Product();
        aplicar(p, dto);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());

        Product guardado = productRepository.save(p);
        guardarImagenes(guardado, dto);
        return guardado;
    }

    @Transactional
    public Product actualizar(Integer id, Map<String, Object> dto) {
        Product p = productRepository.findById(id)
                .orElseThrow(() -> CatalogoException.noEncontrado("El producto no existe"));

        aplicar(p, dto);
        p.setUpdatedAt(LocalDateTime.now());

        Product guardado = productRepository.save(p);
        guardarImagenes(guardado, dto);
        return guardado;
    }

    /**
     * Product.images no tiene cascada, así que la clave ajena de
     * product_images impedía borrar cualquier producto con fotos (es decir,
     * todos): fallaba con un "Datos inválidos" que no decía nada. Las
     * imágenes se borran aquí porque pertenecen al producto.
     *
     * El kardex es otra cosa: si el producto tiene movimientos de inventario,
     * borrarlo destruiría el historial, así que se pide desactivarlo.
     */
    @Transactional
    public void eliminar(Integer id) {
        Product p = productRepository.findById(id)
                .orElseThrow(() -> CatalogoException.noEncontrado("El producto no existe"));

        if (inventoryMovementRepository.existsByProductId(id)) {
            throw CatalogoException.conflicto(
                    "Este producto tiene movimientos de inventario. "
                    + "Desactívalo en vez de eliminarlo para no perder el historial.");
        }

        if (p.getImages() != null && !p.getImages().isEmpty()) {
            productImageRepository.deleteAll(p.getImages());
        }
        productRepository.delete(p);
    }

    // ─── Reglas internas ────────────────────────────────────────

    /**
     * Copia al producto solo las claves que vengan en el dto, para que un
     * PUT parcial no borre lo que no mandó.
     */
    private void aplicar(Product p, Map<String, Object> dto) {
        if (dto.containsKey("name")) p.setName((String) dto.get("name"));

        asignarSlug(p, dto);

        if (dto.containsKey("description")) p.setDescription((String) dto.get("description"));
        if (dto.containsKey("price") && dto.get("price") != null) {
            p.setPrice(decimal(dto.get("price"), "precio"));
        }
        if (dto.containsKey("comparePrice")) {
            p.setDiscountPrice(dto.get("comparePrice") == null
                    ? null
                    : decimal(dto.get("comparePrice"), "precio de comparación"));
        }
        if (dto.containsKey("stock") && dto.get("stock") != null) {
            p.setStock(entero(dto.get("stock"), "stock"));
        }
        if (dto.containsKey("categoryId") && dto.get("categoryId") != null) {
            Integer categoryId = entero(dto.get("categoryId"), "categoría");
            Category categoria = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> CatalogoException.invalida("La categoría elegida no existe"));
            p.setCategory(categoria);
        }
        if (dto.containsKey("subcategoryId") && dto.get("subcategoryId") != null) {
            p.setSubcategoryId(entero(dto.get("subcategoryId"), "subcategoría"));
        }
        if (dto.containsKey("trackStock") && dto.get("trackStock") != null) {
            p.setTrackStock(bandera(dto.get("trackStock")));
        }
        if (dto.containsKey("isFeatured") && dto.get("isFeatured") != null) {
            p.setIsFeatured(bandera(dto.get("isFeatured")));
        }
        if (dto.containsKey("nuevoLanzamiento") && dto.get("nuevoLanzamiento") != null) {
            p.setNuevoLanzamiento(bandera(dto.get("nuevoLanzamiento")));
        }
        if (dto.containsKey("isActive") && dto.get("isActive") != null) {
            p.setIsActive(bandera(dto.get("isActive")));
        }
    }

    /**
     * El slug se deriva del nombre y no se acepta del cliente: el panel no lo
     * envía y la columna es NOT NULL UNIQUE. Al editar solo se recalcula si
     * cambió el nombre, para no romper URLs ya publicadas.
     */
    private void asignarSlug(Product p, Map<String, Object> dto) {
        boolean faltaSlug = p.getSlug() == null || p.getSlug().isBlank();
        boolean cambioElNombre = dto.containsKey("name")
                && !SlugUtils.make(p.getName()).equals(baseDe(p.getSlug()));
        if (!faltaSlug && !cambioElNombre) return;

        String base = SlugUtils.makeOrDefault(p.getName(), "producto");
        final Integer propio = p.getId();
        p.setSlug(SlugUtils.unique(base, s ->
                productRepository.findBySlug(s)
                        .filter(otro -> !otro.getId().equals(propio))
                        .isPresent()));
    }

    /**
     * Las imágenes se reemplazan en bloque: el panel manda la lista completa
     * en el orden en que quedaron, así que reordenar o quitar una es mandar
     * la lista nueva, no un diff.
     */
    private void guardarImagenes(Product p, Map<String, Object> dto) {
        if (!dto.containsKey("images")) return;

        Object crudo = dto.get("images");
        if (!(crudo instanceof List<?> lista)) {
            throw CatalogoException.invalida("El campo 'images' debe ser una lista");
        }

        if (p.getImages() != null && !p.getImages().isEmpty()) {
            productImageRepository.deleteAll(p.getImages());
        }

        int sortOrder = 0;
        for (Object elemento : lista) {
            if (!(elemento instanceof Map<?, ?> imgDto)) {
                throw CatalogoException.invalida("Cada imagen debe ser un objeto con 'url'");
            }
            ProductImage img = new ProductImage();
            img.setProduct(p);
            // Sin firma: el panel reenvía la URL firmada que recibió al leer.
            img.setUrl(s3Service.normalizeUrl(texto(imgDto.get("url"))));
            img.setSortOrder(sortOrder++);
            img.setIsPrimary(Boolean.TRUE.equals(imgDto.get("isPrimary")) ? 1 : 0);
            productImageRepository.save(img);
        }
    }

    /** Quita el sufijo "-2", "-3"… que pone SlugUtils.unique al desempatar. */
    private static String baseDe(String slug) {
        return slug == null ? null : slug.replaceAll("-\\d+$", "");
    }

    private static String texto(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer bandera(Object v) {
        return Boolean.parseBoolean(String.valueOf(v)) ? 1 : 0;
    }

    /**
     * Un número mal escrito da 400 con el nombre del campo, no un 500.
     * Antes un price "abc" salía como NumberFormatException y el panel
     * mostraba "Error inesperado".
     */
    private static Double decimal(Object v, String campo) {
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw CatalogoException.invalida("El " + campo + " no es un número válido");
        }
    }

    private static Integer entero(Object v, String campo) {
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            throw CatalogoException.invalida("El valor de " + campo + " no es un número válido");
        }
    }
}
