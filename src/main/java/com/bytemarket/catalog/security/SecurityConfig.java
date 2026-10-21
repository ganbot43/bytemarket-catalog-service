package com.bytemarket.catalog.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * - /api/admin/**   solo admin y superadmin (rol tomado del JWT)
 * - /api/internal/** llamadas entre microservicios (no se exponen en el gateway)
 * - resto: público; los endpoints de cliente validan la sesión en el controlador
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * El filtro llega como parámetro del @Bean y no como campo inyectado:
     * una clase @Configuration que además produce beans puede entrar en
     * conflicto de creación si se inyecta por constructor, y de este modo
     * la dependencia queda donde se usa.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthFilter,
                                                   ModuloAuthorizationManager porModulo) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.disable())
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                /*
                 * Todo /api/admin lo decide la MATRIZ de permisos, no una
                 * lista de roles escrita aquí.
                 *
                 * Antes había tres reglas en este bloque y la consecuencia
                 * era que la pantalla de Roles y permisos solo escondía el
                 * menú: quitarle Inventario a alguien no le cerraba la API, y
                 * el 8082 está publicado en el compose, así que un curl desde
                 * el host entraba igual.
                 *
                 * Qué módulo corresponde a cada ruta está en RutasDeModulo, y
                 * el método decide si hace falta ver o editar. Lo que esas
                 * tres reglas distinguían a mano —que el cajero LEE el
                 * catálogo para vender pero no lo toca, y que el jefe
                 * CONSULTA el inventario sin moverlo— sigue valiendo: es lo
                 * que dicen los valores por defecto de la matriz.
                 */
                .requestMatchers("/api/admin/**").access(porModulo)
                // Subir imágenes es solo del panel. En arigumi este endpoint
                // es público, pero eso deja que cualquiera llene el bucket:
                // aquí se exige staff a propósito.
                .requestMatchers("/api/upload/**").hasAnyRole("ADMIN", "SUPERADMIN")
                // Las rutas internas NO son públicas, aunque el gateway no
                // las enrute: docker-compose publica el 8082, así que un curl
                // desde el host llegaba igual. Y ahí dentro vive
                // /api/internal/products/stock-decrement, que mueve
                // inventario: estaba cayendo en el permitAll() de abajo y
                // cualquiera podía descontar stock sin identificarse.
                //
                // El rol SERVICE lo trae el JWT que firma order-service con el
                // secreto compartido (ver su ServiceTokenProvider). Se admite
                // también staff para poder depurar estos endpoints a mano
                // desde el panel sin montar un token de servicio.
                .requestMatchers("/api/internal/**").hasAnyRole("SERVICE", "ADMIN", "SUPERADMIN")
                .anyRequest().permitAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> json(res, HttpServletResponse.SC_UNAUTHORIZED, "No autenticado"))
                .accessDeniedHandler((req, res, e) -> json(res, HttpServletResponse.SC_FORBIDDEN, "Acceso denegado"))
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void json(HttpServletResponse res, int status, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"statusCode\":" + status + ",\"message\":\"" + message + "\"}");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
