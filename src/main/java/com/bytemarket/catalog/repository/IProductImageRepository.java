package com.bytemarket.catalog.repository;

import com.bytemarket.catalog.model.Product;
import com.bytemarket.catalog.model.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IProductImageRepository extends JpaRepository<ProductImage, Integer> {
    List<ProductImage> findByProductId(Integer productId);
    void deleteByProduct(Product product);
}
