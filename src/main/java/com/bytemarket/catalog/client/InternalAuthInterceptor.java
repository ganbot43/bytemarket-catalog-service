package com.bytemarket.catalog.client;

import com.bytemarket.catalog.security.ServiceTokenProvider;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pone el token de servicio en cada llamada Feign que salga de aquí.
 *
 * No se reenvía el token de quien hizo la petición original: la matriz se
 * consulta para DECIDIR si esa persona puede entrar, así que pedirla con sus
 * credenciales sería circular. Y además se consulta desde el refresco en
 * segundo plano, donde no hay ninguna petición de la que copiar nada.
 */
@Configuration
@RequiredArgsConstructor
public class InternalAuthInterceptor {

    private final ServiceTokenProvider serviceTokenProvider;

    @Bean
    RequestInterceptor internalAuthRequestInterceptor() {
        return (RequestTemplate template) ->
                template.header("Authorization", "Bearer " + serviceTokenProvider.token());
    }
}
