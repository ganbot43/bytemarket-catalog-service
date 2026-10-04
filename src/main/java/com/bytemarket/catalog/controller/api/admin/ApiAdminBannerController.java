package com.bytemarket.catalog.controller.api.admin;

import com.bytemarket.catalog.model.Banners;
import com.bytemarket.catalog.repository.IBannerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** CRUD de los banners de la portada. */
@RestController
@RequestMapping("/api/admin/banners")
public class ApiAdminBannerController {

    @Autowired
    private IBannerRepository bannerRepository;

    @Autowired
    private com.bytemarket.catalog.service.S3Service s3Service;

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> data = bannerRepository.findAll().stream()
                .sorted(Comparator.comparing(b -> b.getSortOrder() == null ? 0 : b.getSortOrder()))
                .map(this::dto)
                .toList();
        return Map.of("data", data);
    }

    /**
     * imageUrl va firmada para que el panel vea la miniatura. Al guardar,
     * aplicar() le quita la firma, así en base queda siempre la URL estable.
     */
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

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> dto) {
        Banners b = new Banners();
        aplicar(b, dto);
        if (b.getImageUrl() == null) return error(HttpStatus.BAD_REQUEST, "La imagen es requerida");
        return ResponseEntity.status(HttpStatus.CREATED).body(bannerRepository.save(b));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> dto) {
        Banners b = bannerRepository.findById(id).orElse(null);
        if (b == null) return error(HttpStatus.NOT_FOUND, "Banner no encontrado");
        aplicar(b, dto);
        if (b.getImageUrl() == null) return error(HttpStatus.BAD_REQUEST, "La imagen es requerida");
        return ResponseEntity.ok(bannerRepository.save(b));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!bannerRepository.existsById(id)) return error(HttpStatus.NOT_FOUND, "Banner no encontrado");
        bannerRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void aplicar(Banners b, Map<String, Object> dto) {
        // Sin firma: el panel reenvía la URL firmada que recibió al leer.
        if (dto.containsKey("imageUrl")) b.setImageUrl(s3Service.normalizeUrl(texto(dto.get("imageUrl"))));
        if (dto.containsKey("linkUrl")) b.setLinkUrl(texto(dto.get("linkUrl")));
        if (dto.containsKey("eyebrow")) b.setEyebrow(texto(dto.get("eyebrow")));
        if (dto.containsKey("title")) b.setTitle(texto(dto.get("title")));
        if (dto.containsKey("subtitle")) b.setSubtitle(texto(dto.get("subtitle")));
        if (dto.containsKey("ctaLabel")) b.setCtaLabel(texto(dto.get("ctaLabel")));
        if (dto.containsKey("align")) b.setAlign(texto(dto.get("align")));
        if (dto.containsKey("sortOrder") && dto.get("sortOrder") != null) {
            b.setSortOrder(Integer.parseInt(dto.get("sortOrder").toString()));
        }
        // El panel manda isActive como booleano; en base es 0/1.
        if (dto.containsKey("isActive") && dto.get("isActive") != null) {
            b.setIsActive(Boolean.parseBoolean(dto.get("isActive").toString()) ? 1 : 0);
        }
    }

    private static String texto(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("statusCode", status.value(), "message", message));
    }
}
