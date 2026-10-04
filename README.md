# bytemarket-catalog-service

Productos, categorías, banners, reseñas, favoritos e inventario. Es el dueño de las imágenes del catálogo.

Parte del sistema **ByteMarket**, una tienda de repuestos y accesorios para
celulares construida con microservicios Spring Boot y un frontend Nuxt.

**Puerto 8082** · Base de datos `bytemarket_catalog`

## API

Todo entra por el gateway (`http://localhost:8085`), no directamente al 8082.

### Catálogo — público

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/api/products` | Listado con filtros: `categoryId`, `isFeatured`, `nuevoLanzamiento`, `enOferta`, `limit`, `offset` |
| `GET` | `/api/products/{slug}` | Ficha de un producto |
| `GET` | `/api/products/similar?productId=` | Hasta 4 productos relacionados |
| `GET` | `/api/categories?active=true` | Categorías con sus subcategorías y nº de productos |
| `GET` | `/api/banners` | Banners activos de la portada |
| `GET` | `/api/landing/featured-products` | Lo más pedido |
| `GET` | `/api/landing/nuevos-lanzamientos` | Últimos ingresos |

### Reseñas

| Método | Ruta | Acceso | Qué hace |
|---|---|---|---|
| `GET` | `/api/reviews?productId=` | público | Reseñas **aprobadas** y promedio |
| `GET` | `/api/reviews/top` | público | Productos mejor valorados |
| `POST` | `/api/reviews` | cliente | Deja una reseña. Body: `productId`, `rating` (1-5), `comment?` |

Una reseña nueva queda en `pending`: no aparece en la ficha hasta que el
panel la apruebe, porque es texto de terceros en una página pública. Solo se
admite una reseña por persona y producto.

### Favoritos — requiere sesión

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/api/favorites` | Favoritos de la cuenta |
| `POST` | `/api/favorites` | Añade. Body: `productId`. Repetirlo no da error |
| `DELETE` | `/api/favorites/{productId}` | Quita |
| `POST` | `/api/favorites/sync` | **Fusiona** la lista del navegador con la cuenta. Body: `productIds: []` |

El frontend guarda los favoritos en `localStorage` a propósito: quien compra
repuestos suele llegar sin sesión. `/sync` **fusiona**, no reemplaza — si
sobrescribiera, abrir la web en otro dispositivo borraría lo ya guardado. Un
id que ya no existe se ignora sin romper, porque la lista local puede ser
vieja.

### Panel — requiere rol `admin` o `superadmin`

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` `POST` | `/api/admin/products` | Lista (con `totalActive`/`totalInactive`) y alta |
| `PUT` `DELETE` | `/api/admin/products/{id}` | Edita y elimina |
| `GET` `POST` | `/api/admin/categories` | Categorías |
| `PUT` `DELETE` | `/api/admin/categories/{id}` | |
| `GET` `POST` | `/api/admin/subcategories` | Subcategorías |
| `PUT` `DELETE` | `/api/admin/subcategories/{id}` | |
| `GET` `POST` | `/api/admin/banners` | Banners |
| `PUT` `DELETE` | `/api/admin/banners/{id}` | |
| `GET` `POST` | `/api/admin/inventory/movements` | Kardex: consulta y alta manual |
| `GET` `POST` | `/api/admin/reviews` | Moderación de reseñas |
| `PUT` `PATCH` | `/api/admin/reviews/{id}` | Body: `status` = `pending` \| `approved` \| `rejected` |
| `DELETE` | `/api/admin/reviews/{id}` | |
| `POST` | `/api/upload/image` | Sube imagen (`multipart`: `file`, `folder`) |
| `GET` | `/api/upload/presigned?url=` | Vuelve a firmar una URL guardada |

El slug **nunca se toma del cliente**: se deriva del nombre en el servidor y
se desempata con sufijo (`-2`, `-3`) si ya existe.

Un producto con movimientos de inventario no se borra (409): se desactiva,
para no perder el kardex.

### API interna — no expuesta en el gateway

`order-service` la consume por Feign. Queda fuera del ruteo a propósito.

| Método | Ruta | Qué hace |
|---|---|---|
| `POST` | `/api/internal/products/resolve` | Precio, nombre y stock de una lista de ids |
| `POST` | `/api/internal/products/stock-decrement` | Descuenta stock y registra el movimiento |
| `POST` | `/api/internal/products/stock-restore` | Devuelve stock al cancelar |
| `POST` | `/api/internal/products/movements/backfill` | Reconstruye movimientos de pedidos antiguos |
| `GET` | `/api/internal/products/low-stock?threshold=` | Productos bajo el umbral |
| `GET` | `/api/internal/products/export` | Inventario completo para exportar |

### Ejemplo

```bash
curl "http://localhost:8085/api/products?limit=2&isFeatured=1"
```

## Imágenes y S3

El bucket es **privado**: una URL directa devuelve 403. En base se guarda la
URL absoluta del objeto (estable) y se **firma al momento de leer**, válida
dos horas. Así el navegador descarga directo de S3 sin que los bytes pasen
por el backend.

Al guardar se normaliza la URL quitando la firma. Sin eso, el panel reenvía
la URL firmada que recibió y en base quedaría una que caduca, dejando la
imagen rota de forma permanente.

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
