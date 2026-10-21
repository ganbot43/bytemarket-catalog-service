package com.bytemarket.catalog.security;

/**
 * Usuario autenticado extraído del JWT emitido por user-service.
 * El token lleva los claims "uid", "role" y "name", así este servicio no
 * necesita consultar la base de usuarios (que vive en otro microservicio).
 *
 * El nombre puede venir nulo: los tokens emitidos antes de que el claim
 * existiera siguen siendo válidos hasta que caduquen.
 */
public record AuthUser(Long id, String email, String role, String name) {
    public boolean isStaff() {
        return "admin".equals(role) || "superadmin".equals(role);
    }
    public boolean isCustomer() {
        return "customer".equals(role);
    }
}
