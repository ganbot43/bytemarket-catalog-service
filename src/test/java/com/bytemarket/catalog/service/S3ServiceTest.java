package com.bytemarket.catalog.service;

import com.amazonaws.services.s3.AmazonS3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Manejo de URLs de S3.
 *
 * El foco está en normalizeUrl, que evita una corrupción silenciosa: el panel
 * recibe las URLs ya firmadas y las reenvía al guardar. Si se persiste una URL
 * con firma, caduca en dos horas y la imagen queda rota para siempre.
 */
class S3ServiceTest {

    private static final String BUCKET = "joymar-utensilios-assets";
    private static final String OBJETO =
            "https://joymar-utensilios-assets.s3.us-east-2.amazonaws.com/bytemarket/banners/abc-123.png";

    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(mock(AmazonS3.class));
        ReflectionTestUtils.setField(s3Service, "bucketName", BUCKET);
        ReflectionTestUtils.setField(s3Service, "rootPrefix", "bytemarket");
    }

    // ─── normalizeUrl: la guarda contra la corrupción ───────────

    @Test
    @DisplayName("quita la firma: es la forma que debe guardarse en base")
    void quitaLaFirma() {
        String firmada = OBJETO + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Date=20261004T000000Z&X-Amz-Expires=7200"
                + "&X-Amz-Signature=deadbeefcafe&X-Amz-SignedHeaders=host";

        assertEquals(OBJETO, s3Service.normalizeUrl(firmada));
    }

    @Test
    @DisplayName("normalizar es idempotente: aplicarlo dos veces no cambia nada")
    void idempotente() {
        assertEquals(OBJETO, s3Service.normalizeUrl(OBJETO));
        assertEquals(OBJETO, s3Service.normalizeUrl(s3Service.normalizeUrl(OBJETO)));
    }

    @Test
    @DisplayName("deja intactas las URLs que no son de S3")
    void respetaUrlsExternas() {
        String externa = "https://picsum.photos/seed/bytemarket-prod-1/600/600";
        assertEquals(externa, s3Service.normalizeUrl(externa));

        String conQuery = "https://cdn.ejemplo.com/foto.jpg?w=600&h=600";
        assertEquals(conQuery, s3Service.normalizeUrl(conQuery),
                "el ?w=600 de un CDN ajeno no es una firma y no debe recortarse");
    }

    @Test
    @DisplayName("null no revienta")
    void toleraNull() {
        assertNull(s3Service.normalizeUrl(null));
        assertNull(s3Service.getPresignedUrl(null));
    }

    // ─── getPresignedUrl ────────────────────────────────────────

    @Test
    @DisplayName("lo que no es de S3 se devuelve sin firmar: conviven placeholders e imágenes propias")
    void noFirmaLoAjeno() {
        String placeholder = "https://picsum.photos/seed/x/600/600";
        assertEquals(placeholder, s3Service.getPresignedUrl(placeholder));

        String relativa = "/images/logo.png";
        assertEquals(relativa, s3Service.getPresignedUrl(relativa));
    }

    @Test
    @DisplayName("si el cliente de S3 falla, devuelve la original en vez de romper la página")
    void degradaConGracia() {
        // El mock de AmazonS3 devuelve null en generatePresignedUrl, lo que
        // provoca un NPE dentro del método: debe quedar contenido.
        assertEquals(OBJETO, s3Service.getPresignedUrl(OBJETO));
    }
}
