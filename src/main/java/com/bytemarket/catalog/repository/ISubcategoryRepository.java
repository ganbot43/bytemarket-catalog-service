package com.bytemarket.catalog.repository;

import com.bytemarket.catalog.model.Category;
import com.bytemarket.catalog.model.Subcategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ISubcategoryRepository extends JpaRepository<Subcategory, Integer> {
    List<Subcategory> findByCategoryId(Integer categoryId);
    List<Subcategory> findByCategory(Category category);

    /** Necesario para desempatar slugs: la columna es NOT NULL y UNIQUE. */
    Optional<Subcategory> findBySlug(String slug);
}
