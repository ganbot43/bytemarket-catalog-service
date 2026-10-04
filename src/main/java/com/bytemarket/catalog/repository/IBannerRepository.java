package com.bytemarket.catalog.repository;

import com.bytemarket.catalog.model.Banners;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IBannerRepository extends JpaRepository<Banners, Integer> {
    List<Banners> findByIsActiveOrderBySortOrderAsc(Integer isActive);
}
