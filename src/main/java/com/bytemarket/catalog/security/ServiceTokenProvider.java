package com.bytemarket.catalog.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

/**
 * El token con el que este servicio se identifica ante user-service.
 *
 * Es la copia del de order-service, con otro subject. No se comparte la
 * clase por la misma razón que RabbitConfig está duplicado: atar dos
 * servicios al mismo jar para ahorrar treinta líneas sale más caro que la
 * copia.
 *
 * Token de servicio y no de persona: el subject es el nombre del servicio,
 * no lleva uid, y dura cinco minutos porque solo tiene que sobrevivir a la
 * llamada que lo usa.
 */
@Component
public class ServiceTokenProvider {

    private static final String SUBJECT = "bytemarket-catalog-service";
    private static final long VIGENCIA_MS = 5 * 60 * 1000L;

    /** Margen para no reutilizar un token a punto de caducar a mitad de vuelo. */
    private static final long MARGEN_MS = 30 * 1000L;

    @Value("${jwt.secret}")
    private String secret;

    private String cacheToken;
    private long cacheCaduca;

    public synchronized String token() {
        long ahora = System.currentTimeMillis();
        if (cacheToken == null || ahora > cacheCaduca - MARGEN_MS) {
            cacheToken = firmar(ahora);
            cacheCaduca = ahora + VIGENCIA_MS;
        }
        return cacheToken;
    }

    private String firmar(long ahora) {
        return Jwts.builder()
                .setClaims(Map.of("role", "SERVICE"))
                .setSubject(SUBJECT)
                .setIssuedAt(new Date(ahora))
                .setExpiration(new Date(ahora + VIGENCIA_MS))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes()), SignatureAlgorithm.HS256)
                .compact();
    }
}
