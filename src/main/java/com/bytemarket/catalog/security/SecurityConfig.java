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
                                                   JwtAuthenticationFilter jwtAuthFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.disable())
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                /*
                 * El cajero necesita LEER el catálogo para vender —la
                 * pantalla de caja lista productos y busca por nombre— pero
                 * no debe poder tocar nada de él. De ahí que se abra el GET
                 * y no el resto.
                 *
                 * El orden importa: Spring se queda con la primera regla
                 * que encaja.
                 */
                .requestMatchers(HttpMethod.GET, "/api/admin/products/**")
                    .hasAnyRole("CAJERO", "JEFE_VENTAS", "ADMIN", "SUPERADMIN")
                // El inventario lo consulta el jefe al cuadrar.
                .requestMatchers(HttpMethod.GET, "/api/admin/inventory/**")
                    .hasAnyRole("JEFE_VENTAS", "ADMIN", "SUPERADMIN")
                .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "SUPERADMIN")
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
