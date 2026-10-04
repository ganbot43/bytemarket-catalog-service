package com.bytemarket.catalog.controller.api;

import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.repository.IProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/landing")
public class ApiLandingController {

    @Autowired
    private IProductRepository productRepository;

    @Autowired
    private com.bytemarket.catalog.service.ProductPresenter productPresenter;

    // Antes se devolvían las entidades crudas: las imágenes salían con la URL
    // de S3 sin firmar (403 en el navegador) y la tarjeta recibía campos
    // distintos a los del catálogo. ProductPresenter unifica ambas cosas.
    @GetMapping("/featured-products")
    public Map<String, Object> getFeaturedProducts(@RequestParam(defaultValue = "8") int limit) {
        List<Product> products = productRepository.findBestSellers(PageRequest.of(0, limit)).getContent();
        Map<String, Object> response = new HashMap<>();
        response.put("data", productPresenter.toList(products));
        return response;
    }

    @GetMapping("/nuevos-lanzamientos")
    public Map<String, Object> getNuevosLanzamientos(@RequestParam(defaultValue = "12") int limit) {
        List<Product> products = productRepository.findByNuevoLanzamientoAndIsActive(1, 1, PageRequest.of(0, limit)).getContent();
        Map<String, Object> response = new HashMap<>();
        response.put("data", productPresenter.toList(products));
        return response;
    }
}
