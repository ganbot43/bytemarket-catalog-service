package com.bytemarket.catalog.controller.api;

import com.bytemarket.catalog.model.Banners;
import com.bytemarket.catalog.repository.IBannerRepository;
import com.bytemarket.catalog.service.S3Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/banners")
public class ApiBannerController {

    @Autowired
    private IBannerRepository bannerRepository;

    @Autowired
    private S3Service s3Service;

    @GetMapping
    public Map<String, Object> getBanners() {
        // Antes devolvía las entidades crudas y la imageUrl salía sin firmar,
        // así que el navegador recibía un 403 del bucket privado. También
        // devolvía los inactivos: la portada los pintaba igual.
        List<Map<String, Object>> data = bannerRepository.findByIsActiveOrderBySortOrderAsc(1)
                .stream().map(this::dto).toList();

        Map<String, Object> response = new HashMap<>();
        response.put("data", data);
        return response;
    }

    private Map<String, Object> dto(Banners b) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", b.getId());
        m.put("imageUrl", s3Service.getPresignedUrl(b.getImageUrl()));
        m.put("linkUrl", b.getLinkUrl());
        m.put("eyebrow", b.getEyebrow());
        m.put("title", b.getTitle());
        m.put("subtitle", b.getSubtitle());
        m.put("ctaLabel", b.getCtaLabel());
        m.put("align", b.getAlign());
        m.put("sortOrder", b.getSortOrder());
        m.put("isActive", b.getIsActive() != null && b.getIsActive() == 1);
        return m;
    }
}
