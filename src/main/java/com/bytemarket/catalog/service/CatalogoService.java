package com.bytemarket.catalog.service;

import com.bytemarket.catalog.model.Category;
import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.repository.ICategoryRepository;
import com.bytemarket.catalog.repository.IProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@Service
public class CatalogoService {

    @Autowired
    private IProductRepository productRepository;

    @Autowired
    private ICategoryRepository categoryRepository;

    public List<Product> listarNuevosLanzamientos() {
        return productRepository.findByNuevoLanzamientoAndIsActive(1, 1);
    }

    public List<Product> listarProductosActivos() {
        return productRepository.findByIsActive(1);
    }

    public Optional<Product> obtenerProductoPorSlug(String slug) {
        return productRepository.findBySlug(slug);
    }

    // Equivalente a server/api/landing/catalog-categories.get.ts
    public List<Category> listarCategoriasActivas() {
        return categoryRepository.findByIsActiveOrderBySortOrderAsc(1);
    }

    public Page<Product> buscarProductosPaginados(Specification<Product> especificaciones, Pageable paginacion) {
        return productRepository.findAll(especificaciones, paginacion);
    }
}
