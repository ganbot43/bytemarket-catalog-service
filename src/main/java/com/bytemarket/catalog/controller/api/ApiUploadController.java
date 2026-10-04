package com.bytemarket.catalog.controller.api;

import com.bytemarket.catalog.service.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Subida de imágenes del panel (productos, banners, categorías, logo).
 *
 * Vive en catalog-service porque aquí están las entidades que las usan.
 * Devuelve "url" —la URL absoluta de S3, que es lo que se guarda en base—
 * y "previewUrl" ya firmada, para que el panel pueda mostrar la imagen
 * recién subida sin esperar a recargar.
 */
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class ApiUploadController {

    /** Lo que de verdad sabemos servir como imagen. */
    private static final List<String> TIPOS = List.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/svg+xml");

    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final S3Service s3Service;

    /** El panel llama a /api/upload/image; /api/upload queda por compatibilidad. */
    @PostMapping({"", "/image"})
    public ResponseEntity<Map<String, String>> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folder", defaultValue = "products") String folder) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No llegó ningún archivo"));
        }
        if (file.getSize() > MAX_BYTES) {
            return ResponseEntity.status(413).body(Map.of("message", "La imagen no debe pasar de 5 MB"));
        }
        String tipo = file.getContentType();
        if (tipo == null || !TIPOS.contains(tipo.toLowerCase())) {
            return ResponseEntity.status(415)
                    .body(Map.of("message", "Formato no admitido: usa JPG, PNG, WEBP o GIF"));
        }

        try {
            String url = s3Service.uploadFile(file, folder);
            return ResponseEntity.ok(Map.of(
                    "url", url,
                    "previewUrl", s3Service.getPresignedUrl(url)));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message", "No se pudo subir la imagen"));
        }
    }

    /** Vuelve a firmar una URL ya guardada, cuando la anterior caducó. */
    @GetMapping("/presigned")
    public ResponseEntity<Map<String, String>> presigned(@RequestParam("url") String url) {
        return ResponseEntity.ok(Map.of("presignedUrl", s3Service.getPresignedUrl(url)));
    }
}
