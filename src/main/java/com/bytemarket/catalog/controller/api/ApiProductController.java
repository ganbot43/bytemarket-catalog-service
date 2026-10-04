package com.bytemarket.catalog.controller.api;

import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.service.CatalogoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.bytemarket.catalog.repository.ProductoSpecification;

@RestController
@RequestMapping("/api/products")
public class ApiProductController {

    @Autowired
    private CatalogoService catalogoService;

    @Autowired
    private com.bytemarket.catalog.service.ProductPresenter productPresenter;

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> obtenerCatalogoProductos(
            @RequestParam(name = "nuevoLanzamiento", required = false) Integer nuevoLanzamiento,
            @RequestParam(name = "isFeatured", required = false) Integer isFeatured,
            @RequestParam(name = "enOferta", required = false) Integer enOferta,
            @RequestParam(name = "categoryId", required = false) Integer categoryId,
            @RequestParam(name = "categoria", required = false) String categorySlug,
            @RequestParam(name = "q", required = false) String queryBusqueda,
            @RequestParam(name = "precioMin", required = false) Double precioMin,
            @RequestParam(name = "precioMax", required = false) Double precioMax,
            @RequestParam(name = "sort", required = false) String orden,
            @RequestParam(name = "limit", defaultValue = "12") int limite,
            @RequestParam(name = "page", defaultValue = "1") int paginaActual,
            @RequestParam(name = "ids", required = false) List<Integer> ids
    ) {
        
        // Configurar el ordenamiento
        Sort configuracionOrden = Sort.unsorted();
        if (orden != null && !orden.isEmpty()) {
            if (orden.equals("precio-asc")) {
                configuracionOrden = Sort.by(Sort.Direction.ASC, "price");
            } else if (orden.equals("precio-desc")) {
                configuracionOrden = Sort.by(Sort.Direction.DESC, "price");
            } else if (orden.equals("nombre-asc")) {
                configuracionOrden = Sort.by(Sort.Direction.ASC, "name");
            } else if (orden.equals("nombre-desc")) {
                configuracionOrden = Sort.by(Sort.Direction.DESC, "name");
            }
        }

        // Configurar la paginación de Spring Data (page index empieza en 0)
        Pageable opcionesPaginacion = PageRequest.of(paginaActual - 1, limite, configuracionOrden);

        // Armar las especificaciones de búsqueda
        Specification<Product> especificacionesBusqueda = ProductoSpecification.conFiltros(
            nuevoLanzamiento,
            isFeatured,
            categoryId,
            categorySlug,
            queryBusqueda,
            precioMin,
            precioMax,
            1, // isActive = 1
            enOferta,
            ids
        );

        // Buscar productos paginados en la Base de Datos usando el EntityGraph
        Page<Product> resultadosPaginados = catalogoService.buscarProductosPaginados(especificacionesBusqueda, opcionesPaginacion);

        // Convertir los productos al formato web
        List<Map<String, Object>> productosEnFormatoWeb = resultadosPaginados.getContent()
                .stream()
                .map(this::transformProductForFrontend)
                .collect(Collectors.toList());

        // Armar la respuesta
        Map<String, Object> respuestaCatalogo = new HashMap<>();
        respuestaCatalogo.put("data", productosEnFormatoWeb);
        respuestaCatalogo.put("total", resultadosPaginados.getTotalElements());
        respuestaCatalogo.put("page", paginaActual);
        respuestaCatalogo.put("limit", limite);
        
        return ResponseEntity.ok(respuestaCatalogo);
    }

    /** Delega en ProductPresenter, que es quien firma las URLs del bucket. */
    private Map<String, Object> transformProductForFrontend(Product p) {
        return productPresenter.toMap(p);
    }

    // Detalle de producto por slug
    @GetMapping("/{slug}")
    public ResponseEntity<Map<String, Object>> getProductBySlug(@PathVariable("slug") String slug) {
        return catalogoService.obtenerProductoPorSlug(slug)
                .map(p -> ResponseEntity.ok(transformProductForFrontend(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Productos similares
    @GetMapping("/similar")
    public ResponseEntity<Map<String, Object>> obtenerProductosSimilares(@RequestParam("productId") Integer productId) {
        
        // Configuramos paginación solo para 4 resultados
        Pageable opcionesPaginacion = PageRequest.of(0, 4);

        // Evitar el productId actual
        Specification<Product> especificaciones = (root, query, cb) -> cb.and(
            cb.equal(root.get("isActive"), 1),
            cb.notEqual(root.get("id"), productId)
        );

        Page<Product> resultadosPaginados = catalogoService.buscarProductosPaginados(especificaciones, opcionesPaginacion);

        List<Map<String, Object>> productosSimilaresEnFormatoWeb = resultadosPaginados.getContent().stream()
                .map(this::transformProductForFrontend)
                .collect(Collectors.toList());

        Map<String, Object> respuestaSimilares = new HashMap<>();
        respuestaSimilares.put("data", productosSimilaresEnFormatoWeb);
        
        return ResponseEntity.ok(respuestaSimilares);
    }
}
