package com.bytemarket.catalog.service;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Date;
import java.util.UUID;

/**
 * Subida y lectura de imágenes en S3.
 *
 * El bucket es privado: una URL directa devuelve 403. En base de datos se
 * guarda la URL absoluta del objeto (estable, no caduca) y al devolverla al
 * cliente se firma con {@link #getPresignedUrl}, válida dos horas. Así el
 * navegador descarga directo de S3 y las imágenes no pasan por el backend.
 */
@Service
@RequiredArgsConstructor
public class S3Service {

    private final AmazonS3 s3Client;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Value("${aws.s3.root-prefix}")
    private String rootPrefix;

    public String uploadFile(MultipartFile file, String folder) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : "";
        String uniqueFileName = UUID.randomUUID() + extension;
        String key = rootPrefix + "/" + folder + "/" + uniqueFileName;

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(file.getContentType());
        metadata.setContentLength(file.getSize());

        s3Client.putObject(new PutObjectRequest(bucketName, key, file.getInputStream(), metadata));

        return s3Client.getUrl(bucketName, key).toString();
    }

    /**
     * Devuelve la forma que debe guardarse en base: la URL del objeto sin la
     * firma.
     *
     * Hace falta porque el panel recibe las URLs ya firmadas y las reenvía al
     * guardar. Sin esto se persistiría una URL con firma, y al caducar la
     * imagen quedaría rota de forma permanente. Una URL firmada es la del
     * objeto más los parámetros X-Amz-*, así que basta cortar en el "?".
     */
    public String normalizeUrl(String fileUrl) {
        if (fileUrl == null || !fileUrl.contains(".amazonaws.com/")) {
            return fileUrl;
        }
        int q = fileUrl.indexOf('?');
        return q < 0 ? fileUrl : fileUrl.substring(0, q);
    }

    /**
     * Convierte la URL guardada en una URL firmada que el navegador puede
     * abrir. Lo que no sea de S3 (una imagen externa, un placeholder) se
     * devuelve tal cual: así conviven las dos cosas en el catálogo.
     */
    public String getPresignedUrl(String fileUrl) {
        if (fileUrl == null || !fileUrl.contains(".amazonaws.com/")) {
            return fileUrl;
        }
        try {
            String key = fileUrl.substring(fileUrl.indexOf(".amazonaws.com/") + 15);
            Date expiration = new Date();
            expiration.setTime(expiration.getTime() + 1000L * 60 * 60 * 2); // 2 horas

            GeneratePresignedUrlRequest request =
                    new GeneratePresignedUrlRequest(bucketName, key)
                            .withMethod(HttpMethod.GET)
                            .withExpiration(expiration);

            return s3Client.generatePresignedUrl(request).toString();
        } catch (Exception e) {
            // Si no se puede firmar, mejor devolver la original que romper la página.
            return fileUrl;
        }
    }
}
