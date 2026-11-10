# Guía integral del proyecto Precio Claro

## 1. Propósito

Precio Claro permite administrar reglas comerciales por producto y consultar una cotización consolidada a partir de dos proveedores externos. La aplicación calcula el precio final con el margen configurado para cada SKU y busca mantener disponible la consulta cuando una integración es lenta o falla.

Esta guía reúne el propósito del sistema, sus decisiones tecnológicas, arquitectura, reglas de negocio, contratos, operación y límites conocidos. Para instrucciones rápidas de ejecución, consultar el [README](../README.md); para el detalle de implementación y evidencia de pruebas, consultar el [reporte técnico](./REPORTE_IMPLEMENTACION.md).

## 2. Qué resuelve

El catálogo mantiene una regla por SKU. Cada regla define el nombre visible, el margen porcentual, el proveedor preferido y si el producto está activo. Al consultar un SKU activo, el backend obtiene el precio base de un proveedor o de Redis, aplica el margen vigente y devuelve tanto el precio final como el origen, frescura y latencia.

Los proveedores A y B están simulados con WireMock. Esto permite desarrollar y probar situaciones reproducibles —latencia, datos antiguos, errores y límite de tasa— sin depender de servicios externos reales.

## 3. Arquitectura

```mermaid
flowchart LR
    U[Usuario] --> SPA[React + TypeScript]
    SPA -->|HTTP /api| N[Nginx]
    N --> API[Spring Boot REST]
    API -->|reglas y transacciones| PG[(PostgreSQL)]
    API -->|cotizaciones y cuota B| R[(Redis)]
    API -->|GET /a/prices/{sku}| A[WireMock · proveedor A]
    API -->|GET /b/prices/{sku}| B[WireMock · proveedor B]
    A --> API
    B --> API
```

### Responsabilidades

| Componente | Responsabilidad |
|---|---|
| SPA | Alta, edición, eliminación y consulta desde el navegador; muestra estados, errores y desglose del precio. |
| Nginx | Sirve la aplicación compilada y reenvía `/api/` al backend. |
| API Spring Boot | Aplica reglas de negocio, valida solicitudes, coordina persistencia, caché y proveedores, y traduce errores a HTTP. |
| PostgreSQL | Persiste las reglas comerciales y garantiza unicidad e integridad mediante restricciones. |
| Redis | Comparte cotizaciones cacheadas y controla de forma atómica la cuota global del proveedor B. |
| Clientes A/B | Adaptan cada contrato externo a un modelo de cotización común, con timeout, circuit breaker y bulkhead. |
| WireMock | Simula los contratos y escenarios de ambos proveedores para el entorno local y las pruebas manuales. |

La aplicación está organizada como un monolito modular: reglas de producto, consulta de precios, adaptadores externos, API y configuración están separados en paquetes, pero se despliegan como un único backend. No hay comunicación directa del navegador con los proveedores.

## 4. Tecnologías y por qué se usan

| Tecnología | Uso y motivo |
|---|---|
| Java 21 | Lenguaje y runtime del backend; versión definida en Maven y en la imagen de ejecución. |
| Spring Boot 3.4 | Aplicación backend y configuración integrada de web, validación, persistencia, Redis y Actuator. |
| Spring Web / `RestClient` | Endpoints REST y llamadas HTTP síncronas a los proveedores con timeouts acotados. |
| Spring Validation | Reglas declarativas para campos de entrada antes de ejecutar la lógica de negocio. |
| Spring Data JPA / Hibernate | Mapeo de reglas a entidades relacionales y acceso a PostgreSQL. Hibernate valida el esquema (`ddl-auto: validate`) en vez de modificarlo automáticamente. |
| PostgreSQL 16 | Persistencia duradera, tipos relacionales, UUID, unicidad de SKU y restricciones de margen/proveedor. |
| Flyway | Cambios de esquema versionados y carga reproducible de una regla de demostración. |
| Redis 7 | Compartición de caché entre instancias y ejecución de un script Lua atómico para el rate limit distribuido. |
| Resilience4j 2.2 | Circuit breaker independiente para cada proveedor. |
| React 19 + TypeScript 5 | SPA interactiva, con tipado estático de reglas, respuestas y cliente API. |
| Vite 6 | Servidor de desarrollo y compilación optimizada de los recursos web. |
| Nginx | Servidor liviano de producción para los assets compilados y reverse proxy a la API. |
| WireMock 3.13 | Stubs HTTP configurables para respuestas, fallas y latencias previsibles. |
| Docker Compose | Arranque conjunto y configuración de red/health checks para los cinco servicios. |
| JUnit 5 / Spring Boot Test | Pruebas de lógica y contratos HTTP; Redis real se usa para comprobar el script Lua cuando está disponible. |

Los números de versión y dependencias del backend se definen en [backend/pom.xml](../backend/pom.xml); las del frontend, en [frontend/package.json](../frontend/package.json).

## 5. Modelo de dominio y reglas comerciales

La entidad principal es `ProductRule`:

| Campo | Significado |
|---|---|
| `id` | Identificador UUID interno. |
| `sku` | Identificador comercial único del producto, hasta 64 caracteres. |
| `name` | Nombre visible, hasta 160 caracteres. |
| `marginPercentage` | Margen entre 0 y 1000, con precisión de dos decimales. |
| `preferredProvider` | `AUTO`, `PROVIDER_A` o `PROVIDER_B`. |
| `active` | Indica si el producto puede consultarse. |
| `updatedAt` | Momento de última escritura. |
| `version` | Versión optimista de JPA, utilizada también para aislar las cotizaciones cacheadas. |

Al crear y buscar reglas, el backend elimina espacios exteriores y canonicaliza el SKU a mayúsculas. La interfaz también lo muestra en mayúsculas mientras se escribe. La búsqueda ignora casing para tolerar registros anteriores con otra capitalización; la base de datos mantiene la unicidad del valor persistido. El SKU no se modifica en una operación `PUT`.

El margen se aplica en el backend, nunca en la interfaz:

```text
precioFinal = precioProveedor × (1 + margenPorcentual / 100)
```

El resultado se redondea a dos decimales con `HALF_UP`. Ejemplo: precio base de 100.00 y margen de 15% producen 115.00.

## 6. Lógica de consulta de precios

### Secuencia

1. El backend normaliza el SKU y carga la regla desde PostgreSQL.
2. Si no existe o está inactiva, responde `404`. La comprobación ocurre antes de consultar Redis o los proveedores.
3. Busca una cotización en Redis con la clave `price:v1:{sku}:{ruleId}:{ruleVersion}`.
4. Si la cotización es fresca, devuelve esa cotización y aplica el margen actual de la regla.
5. Si no hay cotización fresca, el proveedor preferido determina la ruta.
6. Si un proveedor responde, el backend normaliza la cotización, la almacena y genera el precio final.
7. Ante fallos o falta de permiso para llamar a B, se usa una cotización retenida como respaldo, si existe. De lo contrario se devuelve `503`.

### Selección de proveedor

| Preferencia | Comportamiento después de un cache miss |
|---|---|
| `AUTO` | Consulta A primero. Si A falla, intenta B de forma síncrona. Si A devuelve una cotización de más de 15 minutos, la respuesta se marca stale y se programa una actualización asíncrona con B. |
| `PROVIDER_A` | Sigue la misma ruta de A primero y failover a B que `AUTO`. |
| `PROVIDER_B` | Consulta B directamente; no usa A como failover. |

Una entrada cacheada es fresca si fue guardada en los últimos 60 segundos y la cotización del proveedor no supera 15 minutos de antigüedad. Redis conserva la entrada durante 15 minutos desde que se guarda; durante esa retención puede servir de fallback y se devuelve con `stale: true`. El margen no se guarda dentro de la cotización: se toma de la regla actual en cada respuesta.

Las respuestas de precio informan una fuente (`CACHE`, `PROVIDER_A`, `PROVIDER_B` o `FALLBACK_CACHE`), un indicador `stale` y la latencia observada por el backend. Una respuesta stale sigue siendo explícitamente identificable para la interfaz y para el consumidor de la API.

### Concurrencia y actualización de reglas

Un mecanismo `single-flight` local comparte el resultado de una consulta externa cuando varias solicitudes simultáneas de la misma combinación SKU, ID y versión de regla llegan a una misma instancia. Evita repetir el fetch en esa instancia, pero no es un bloqueo distribuido entre réplicas.

La clave de caché incluye el ID y la versión de la regla para separar datos incluso si se elimina y luego se recrea el mismo SKU. Al editar o eliminar se invalida la clave anterior. Al cambiar una regla, el precio final se recalcula con el margen nuevo, aunque el precio base provenga de una cotización reutilizable.

## 7. Resiliencia e integraciones

| Mecanismo | Configuración implementada | Propósito |
|---|---|---|
| Timeouts HTTP | Conexión hasta 100 ms; lectura A 250 ms y B 650 ms. | Limitar el tiempo que una llamada externa puede ocupar una solicitud. |
| Circuit breaker | Uno por proveedor; ventana de 10 llamadas, mínimo 10, apertura con 50% de fallos, abierto 10 s y hasta 3 llamadas de prueba en half-open. | Dejar de invocar temporalmente un proveedor que está fallando. |
| Bulkhead | Semáforo no bloqueante: 32 llamadas simultáneas a A y 5 a B por instancia. | Acotar concurrencia; las solicitudes excedentes no esperan en una cola. |
| Límite de B | Máximo 5 llamadas en cualquier ventana de 1000 ms, compartido en Redis y actualizado con Lua atómico. | Respetar la cuota de B incluso cuando hay más de una instancia. |
| Reintentos | No hay reintentos automáticos de B. | Evitar llamadas duplicadas, consumo adicional de cuota y latencia extra. |
| Refresh stale | Executor local acotado: 2–4 hilos y cola de 100 tareas. | Intentar actualizar en segundo plano sin bloquear la respuesta stale. |
| Fallback | Cotización guardada utilizable hasta la retención de 15 minutos. | Mantener una respuesta degradada cuando no se puede consultar al proveedor. |

La actualización de cotizaciones stale y `single-flight` son locales a cada proceso. La caché y la cuota de B sí son compartidas a través de Redis.

## 8. API REST

Base: `/api/v1`.

| Método | Ruta | Comportamiento |
|---|---|---|
| `POST` | `/product-rules` | Crea una regla y responde `201` con `Location`; SKU duplicado devuelve `409`. |
| `GET` | `/product-rules` | Lista las reglas ordenadas por SKU. |
| `PUT` | `/product-rules/{sku}` | Actualiza nombre, margen, proveedor y estado; invalida caché anterior. |
| `DELETE` | `/product-rules/{sku}` | Elimina la regla e invalida caché; responde `204`. |
| `GET` | `/prices?sku={sku}` | Consulta y consolida el precio para una regla activa. |

Validaciones principales: SKU no vacío con caracteres alfanuméricos, punto, guion o guion bajo; nombre no vacío; margen de 0 a 1000; proveedor requerido. Los errores siguen un cuerpo uniforme con `timestamp`, `status`, `error`, `message` y `fields`. Se utilizan `400` para validación, `404` para regla inexistente/inactiva, `409` para duplicado y `503` cuando no hay proveedor ni fallback.

## 9. Interfaz de usuario

La SPA ofrece un catálogo con nombre, SKU, margen, proveedor y estado. Desde allí se puede crear, editar o eliminar una regla, actualizar la lista y solicitar el precio de un SKU activo. La consulta muestra precio base, margen, precio final, moneda, fuente, antigüedad y latencia.

Los estados de carga y error se muestran en la página o en el monitor de consulta. Al editar o eliminar una regla se limpia el resultado del SKU afectado para evitar presentar un desglose que ya no representa la regla vigente. Los avisos de éxito se ocultan a los cuatro segundos; los errores permanecen hasta otra acción. La interfaz también adapta el desplazamiento al formulario/monitor y respeta la preferencia del usuario por movimiento reducido.

## 10. Persistencia y despliegue local

Flyway crea `product_rules` y registra una regla demo: `ABC-123`, margen 15%, modo `AUTO`. Hibernate valida el esquema existente y no crea ni modifica tablas al iniciar.

Para levantar toda la aplicación se requiere Docker Compose v2:

```sh
docker compose up --build
```

| Servicio | Dirección local | Uso |
|---|---|---|
| Frontend | `http://localhost:3000` | Aplicación web servida por Nginx. |
| Backend | `http://localhost:8080` | API y Actuator. |
| API | `http://localhost:8080/api/v1` | Endpoints REST. |
| PostgreSQL | `localhost:5432` | Base `prices`. |
| Redis | `localhost:6379` | Caché y rate limit. |
| WireMock | `http://localhost:8082` | Simuladores y consola admin. |

Compose configura health checks para PostgreSQL y Redis y espera al backend saludable antes de iniciar el frontend. Para detener los servicios conservando los datos, ejecutar `docker compose down`; `docker compose down -v` también elimina el volumen persistente de PostgreSQL.

Los parámetros de conexión y proveedores se pueden sobreescribir mediante `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `PROVIDER_A_URL`, `PROVIDER_B_URL`, `PROVIDER_A_TIMEOUT_MS` y `PROVIDER_B_TIMEOUT_MS`. En desarrollo sin contenedores de aplicación se pueden iniciar las dependencias con Compose, ejecutar el backend con Maven y el frontend con Vite; Vite reenvía `/api` a `localhost:8080`.

## 11. Pruebas y validación

La suite documentada contiene 22 pruebas JUnit 5 distribuidas entre contratos HTTP, reglas y caché, failover/fallback/concurrencia, circuit breaker y rate limit en Redis. Incluye controles deterministas de reloj y stubs; la prueba del script Lua requiere Redis en `localhost:6379` y se omite explícitamente si no está disponible.

Comandos habituales:

```sh
mvn -f backend/pom.xml test
npm --prefix frontend install
npm --prefix frontend run build
```

La matriz de casos y el resultado de validación descritos en el reporte técnico están en [docs/REPORTE_IMPLEMENTACION.md](./REPORTE_IMPLEMENTACION.md).

## 12. Estructura principal del repositorio

| Ruta | Contenido |
|---|---|
| `backend/src/main/java/.../rules/` | Entidad, DTOs, repositorio, servicio y controller de reglas. |
| `backend/src/main/java/.../pricing/` | Consulta, caché, contratos normalizados, clientes A/B y rate limiter. |
| `backend/src/main/java/.../api/` | Manejo uniforme de errores de API. |
| `backend/src/main/java/.../config/` | Reloj inyectable y executor para refresh. |
| `backend/src/main/resources/application.yml` | Conexiones, timeouts, health y circuit breakers. |
| `backend/src/main/resources/db/migration/` | Esquema y datos de demostración versionados. |
| `backend/src/test/java/` | Pruebas del backend. |
| `frontend/src/` | SPA, estilos y cliente/tipos de la API. |
| `stubs/mappings/` | Escenarios de WireMock para proveedores. |
| `docs/diagramas/` | Diagramas visuales y editables. |
| `docker-compose.yml` | Definición del entorno completo local. |

## 13. Decisiones y límites conocidos

- Los proveedores son simulaciones locales; para integrarse con proveedores reales habría que sustituir sus URLs/contratos y validar autenticación, términos de cuota, datos y errores reales.
- Los timeouts acotan las llamadas, pero no garantizan por sí solos un SLA p95. En particular, el camino síncrono a B puede superar 800 ms según la latencia real; para afirmar un SLA se necesita medir bajo carga representativa, incluyendo base de datos, Redis, JVM y red.
- El `single-flight` y el pool de refresh son por instancia, no distribuidos. Redis comparte datos y cuota, pero no la ejecución pendiente.
- Compose usa credenciales de demostración y el proyecto no implementa autenticación ni autorización. No debe exponerse como instalación de producción sin controles de acceso, secretos administrados, monitoreo y configuración operativa adecuados.
- Redis es necesario para la caché y para el límite de tasa de B; su disponibilidad forma parte de la operación de consulta.
- Los stubs deterministas facilitan reproducir fallos, pero no sustituyen pruebas de contrato y carga contra integraciones reales.

## 14. Glosario

- **SKU:** código que identifica un producto en el catálogo.
- **Cotización:** precio base recibido de un proveedor, con moneda, proveedor y momento de actualización.
- **Fresca:** cotización dentro de la ventana normal de reutilización.
- **Stale:** cotización cuya marca de tiempo supera 15 minutos; se informa explícitamente y puede usarse como respaldo durante su retención.
- **Fallback:** respuesta cacheada que permite degradar la consulta cuando no hay proveedor disponible o no hay permiso para consultar B.
- **Circuit breaker:** mecanismo que suspende llamadas a un proveedor que presenta fallos repetidos y luego permite llamadas de prueba.
- **Bulkhead:** límite de concurrencia independiente para evitar que una integración consuma recursos sin control.
- **Single-flight:** coalescencia de solicitudes simultáneas equivalentes dentro de una misma instancia.
