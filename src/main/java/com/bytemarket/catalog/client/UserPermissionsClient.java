package com.bytemarket.catalog.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

/**
 * La matriz de permisos, que vive en user-service.
 *
 * Se consulta en vez de llevarla en el JWT porque el gerente la cambia en
 * caliente: con los permisos dentro del token, quitarle un módulo a alguien
 * no haría efecto hasta que cerrara sesión, y una pantalla de permisos cuyos
 * cambios tardan un día en notarse se lee como que está rota.
 *
 * Su fallback NO relanza: devuelve vacío y PermissionsCache decide qué hacer
 * con eso. Que user-service esté caído no puede dejar el panel inservible,
 * pero tampoco puede abrir puertas: la política está documentada allí.
 */
@FeignClient(
        name = "bytemarket-user-service",
        contextId = "userPermissions",
        path = "/api/internal",
        fallback = UserPermissionsClientFallback.class)
public interface UserPermissionsClient {

    @GetMapping("/permissions")
    Map<String, Object> matriz();
}
