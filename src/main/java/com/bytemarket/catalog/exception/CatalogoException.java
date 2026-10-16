package com.bytemarket.catalog.exception;

/**
 * Una regla del catálogo que no se cumple.
 *
 * Mismo planteamiento que ComplaintException en support-service: lleva el
 * estado HTTP porque las reglas no fallan todas igual —un nombre vacío es
 * 400, un producto que no existe es 404 y borrar uno con kardex es 409— y
 * esa distinción tenía que salir de los `return error(…)` repartidos por el
 * controlador para poder vivir en el servicio.
 *
 * Lo importante es que el servicio no devuelve ResponseEntity: lanza esto, y
 * quien traduce a HTTP es el GlobalExceptionHandler. Así las mismas reglas
 * valen si mañana un producto se crea desde un importador CSV o desde un
 * evento de cola, donde no hay respuesta HTTP que devolver.
 */
public class CatalogoException extends RuntimeException {

    private final int status;

    public CatalogoException(int status, String mensaje) {
        super(mensaje);
        this.status = status;
    }

    public static CatalogoException invalida(String mensaje) {
        return new CatalogoException(400, mensaje);
    }

    public static CatalogoException noEncontrado(String mensaje) {
        return new CatalogoException(404, mensaje);
    }

    public static CatalogoException conflicto(String mensaje) {
        return new CatalogoException(409, mensaje);
    }

    public int getStatus() {
        return status;
    }
}
