# bytemarket-catalog-service

Productos, categorías, banners, reseñas, favoritos e inventario. Es el dueño de las imágenes del catálogo.

Parte del sistema **ByteMarket**, una tienda de repuestos y accesorios para
celulares construida con microservicios Spring Boot y un frontend Nuxt.

## Qué hace

- Catálogo público con filtros, búsqueda y paginación
- CRUD de productos, categorías, subcategorías y banners
- **Kardex de inventario**: cada venta deja su movimiento
- **Reseñas con moderación**: una reseña nueva queda `pending` hasta que el panel la apruebe
- **Favoritos** sincronizables con la cuenta
- **Subida de imágenes a S3** (`/api/upload/image`)

### Imágenes y S3

El bucket es **privado**: una URL directa devuelve 403. En base se guarda la
URL absoluta del objeto (estable) y se **firma al momento de leer**, válida
dos horas. Así el navegador descarga directo de S3 sin que los bytes pasen
por el backend.

Al guardar se normaliza la URL quitando la firma. Sin eso, el panel reenvía
la URL firmada que recibió y en base quedaría una que caduca, dejando la
imagen rota de forma permanente.

Base de datos: `bytemarket_catalog`

## Cómo levantarlo

Requisitos: **Java 17+**, **MySQL 8** en `localhost:3306` y el
`bytemarket-eureka-server` ya arrancado (salvo que este repo *sea* Eureka).

```bash
cp .env.example .env     # y rellena los valores
./mvnw spring-boot:run
```

Queda escuchando en el puerto **8082**. El esquema de base de datos se crea
solo al arrancar (`createDatabaseIfNotExist=true`).

## Configuración

Las credenciales se leen del `.env`, que **no se versiona**. Los
`application*.yml` solo traen marcadores: si falta el `.env`, los valores
sensibles quedan vacíos. Mira `.env.example` para saber qué rellenar.

> El `JWT_SECRET` debe ser **idéntico** en user, catalog, order y support:
> user-service firma el token y los demás verifican la firma. Si difieren,
> todas las peticiones autenticadas fallan con 401 sin dejar rastro en el log.

## El sistema completo

| Repositorio | Puerto | Función |
|---|---|---|
| `bytemarket-eureka-server` | 8761 | Registro de servicios |
| `bytemarket-api-gateway` | 8085 | Punto de entrada único; enruta a los demás |
| `bytemarket-user-service` | 8081 | Cuentas, autenticación JWT, perfiles |
| `bytemarket-catalog-service` | 8082 | Productos, categorías, banners, inventario |
| `bytemarket-order-service` | 8083 | Pedidos, métodos de pago, cupones, reportes |
| `bytemarket-support-service` | 8084 | Libro de reclamaciones |
| `frontend-bytemarket` | 3000 | Tienda y panel de administración (Nuxt 3) |

Orden de arranque: **Eureka primero**, luego los servicios de negocio, el
gateway al final y el frontend cuando el gateway responda.
