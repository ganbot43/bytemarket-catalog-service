package com.bytemarket.catalog.repository;

import com.bytemarket.catalog.model.Favorite;
import com.bytemarket.catalog.model.Product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, Integer> {
    
    List<Favorite> findByUserIdOrderByCreatedAtDesc(Long userId);
    
    @Query("SELECT f.product FROM Favorite f WHERE f.userId = :userId ORDER BY f.createdAt DESC")
    List<Product> findProductsByUserId(@Param("userId") Long userId);
    
    boolean existsByUserIdAndProduct(Long userId, Product product);
    
    Optional<Favorite> findByUserIdAndProduct(Long userId, Product product);

    @Modifying
    @Query("DELETE FROM Favorite f WHERE f.userId = :userId AND f.product = :product")
    void deleteByUserIdAndProduct(@Param("userId") Long userId, @Param("product") Product product);
}
