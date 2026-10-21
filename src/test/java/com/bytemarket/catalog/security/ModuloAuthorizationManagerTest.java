package com.bytemarket.catalog.security;

import com.bytemarket.catalog.security.PermissionsCache.Permiso;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * El portero de /api/admin: decide con la matriz de permisos.
 *
 * Antes eran tres reglas hasAnyRole en el SecurityConfig, así que la pantalla
 * de Roles y permisos solo escondía el menú: quitarle Inventario a alguien no
 * le cerraba la API, y el 8082 está publicado en el compose.
 */
@DisplayName("Permisos — el portero de /api/admin en catálogo")
class ModuloAuthorizationManagerTest {

    private PermissionsCache cache;
    private ModuloAuthorizationManager portero;

    private static final Permiso NADA = new Permiso(false, false, false, false);
    private static final Permiso VER = new Permiso(true, false, false, false);
    private static final Permiso TODO = new Permiso(true, true, true, true);

    @BeforeEach
    void setUp() {
        cache = mock(PermissionsCache.class);
        when(cache.permiso(any(), any())).thenReturn(NADA);
        portero = new ModuloAuthorizationManager(cache);
    }

    private boolean permite(String rol, String metodo, String ruta) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "quien@bytemarket.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + rol.toUpperCase())));
        var decision = portero.check(() -> auth,
                new RequestAuthorizationContext(new MockHttpServletRequest(metodo, ruta)));
        return decision != null && decision.isGranted();
    }

    @Test
    @DisplayName("el gerente entra a todo sin consultar la matriz")
    void elGerenteEntraATodo() {
        assertTrue(permite("superadmin", "POST", "/api/admin/products"));
        assertTrue(permite("superadmin", "DELETE", "/api/admin/banners/3"));
        verify(cache, never()).permiso(any(), any());
    }

    @Test
    @DisplayName("el cajero lee el catálogo desde su módulo Caja, pero no lo toca")
    void elCajeroLeeElCatalogoParaVender() {
        /* El matiz que las reglas viejas distinguían a mano: la pantalla de
           caja lista productos y busca por nombre, así que el GET le hace
           falta para cobrar. Pero el permiso que lo habilita es "caja", no
           "productos": si dependiera de Productos, abrirle el catálogo para
           vender le abriría también el alta y el borrado. */
        when(cache.permiso(eq("cajero"), eq("caja"))).thenReturn(TODO);

        assertTrue(permite("cajero", "GET", "/api/admin/products"));
        assertTrue(permite("cajero", "GET", "/api/admin/products/42"));
        assertFalse(permite("cajero", "POST", "/api/admin/products"),
                "leer para vender no es poder crear productos");
        assertFalse(permite("cajero", "DELETE", "/api/admin/products/42"));
    }

    @Test
    @DisplayName("quien tiene Productos también lee el catálogo, sin necesitar Caja")
    void productosTambienAbreLaLectura() {
        // La otra mitad del caso anterior: si la ruta perteneciera solo a
        // "caja", conceder Productos a alguien no le dejaría ver ninguno.
        when(cache.permiso(eq("admin"), eq("productos"))).thenReturn(TODO);

        assertTrue(permite("admin", "GET", "/api/admin/products"));
        assertTrue(permite("admin", "POST", "/api/admin/products"));
    }

    @Test
    @DisplayName("el jefe consulta el inventario pero no lo mueve")
    void verNoAlcanzaParaEscribir() {
        when(cache.permiso(eq("jefe_ventas"), eq("inventario"))).thenReturn(VER);

        assertTrue(permite("jefe_ventas", "GET", "/api/admin/inventory/movements"));
        assertFalse(permite("jefe_ventas", "POST", "/api/admin/inventory/movements"),
                "mover stock es de quien reabastece");
    }

    @Test
    @DisplayName("sin el módulo no se entra, aunque el rol sea de panel")
    void sinModuloNoSeEntra() {
        assertFalse(permite("jefe_ventas", "GET", "/api/admin/categories"));
        assertFalse(permite("cajero", "GET", "/api/admin/inventory/movements"));
    }

    @Test
    @DisplayName("una ruta de /api/admin sin módulo declarado NO pasa")
    void deniegaPorOmision() {
        /* Si mañana se añade un controlador y nadie lo declara en
           RutasDeModulo, quedarse fuera se nota en la primera prueba; pasar
           sin comprobar nada no se nota hasta que es tarde. */
        assertFalse(permite("admin", "GET", "/api/admin/modulo-que-nadie-declaro"));
    }

    @Test
    @DisplayName("el token de servicio NO entra por /api/admin")
    void elTokenDeServicioNoEntraPorAqui() {
        /* Lo había dejado pasar por una suposición falsa: antes de la matriz,
           /api/admin/** era ADMIN+SUPERADMIN sin SERVICE. Las llamadas entre
           servicios van por /api/internal/**, que tiene su propia regla, así
           que abrirle esto era una puerta nueva y no una compatibilidad. */
        assertFalse(permite("service", "POST", "/api/admin/inventory/movements"));
        assertFalse(permite("service", "GET", "/api/admin/products"));
    }

    @Test
    @DisplayName("un cliente de la tienda no entra, y sin sesión tampoco")
    void losDeFueraNoEntran() {
        assertFalse(permite("customer", "GET", "/api/admin/products"));
        var decision = portero.check(() -> null,
                new RequestAuthorizationContext(new MockHttpServletRequest("GET", "/api/admin/products")));
        assertFalse(decision.isGranted());
    }
}
