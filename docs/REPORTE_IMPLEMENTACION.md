# Reporte técnico de implementación

## Propósito

Este documento explica qué se construyó para la prueba técnica, cómo se conectan sus partes, dónde vive cada responsabilidad y qué evidencia existe de su comportamiento. No sustituye el README de ejecución: sirve como guía de lectura y defensa técnica del proyecto.

## Resumen ejecutivo

Precio Claro es un middleware de precios con una SPA para administrar reglas comerciales. El frontend React llama a una API Spring Boot; las reglas viven en PostgreSQL, las cotizaciones y el control distribuido de cuota en Redis, y WireMock simula ambos proveedores. La entrega se levanta con Docker Compose.

Se implementaron CRUD de reglas, consulta de precio y margen, caché fresca y stale, failover, circuit breakers, bulkheads, límite estricto de B, deduplicación concurrente por SKU/regla y respuestas de error controladas. La suite contiene 22 pruebas JUnit 5. El frontend tiene flujos de alta/edición/eliminación, consulta, feedback temporal y monitor de precios.

## Diagramas editables

Los archivos son escenas nativas de Excalidraw; se pueden abrir/importar y modificar elemento por elemento:

- [Arquitectura de extremo a extremo](diagramas/arquitectura.excalidraw)
- [Flujo de consulta y degradación](diagramas/flujo-consulta-precio.excalidraw)

El README raíz contiene además un diagrama Mermaid de referencia.

## Arquitectura

```mermaid
flowchart LR
    User[Usuario] --> SPA[React + TypeScript\nPuerto 3000]
    SPA -->|/api| Nginx[Nginx reverse proxy]
    Nginx --> API[Spring Boot REST\nPuerto 8080]
    API -->|CRUD ProductRule| PG[(PostgreSQL 16\nFlyway)]
    API -->|Cotización y rate limit Lua| Redis[(Redis 7)]
    API -->|GET /a/prices/{sku}| A[WireMock A]
    API -->|GET /b/prices/{sku}| B[WireMock B]
    A --> API
    B --> API
```

### Límites de responsabilidad

- **SPA (`frontend/`)**: captura reglas y acciones del usuario; presenta carga, errores y datos. No decide el margen ni el failover.
- **Nginx**: entrega los assets de Vite compilados y enruta `/api/` al backend dentro de Compose.
- **Controllers**: exponen contratos REST; reciben/validan DTOs y delegan el trabajo.
- **Rules service**: canonicaliza SKU, persiste CRUD y coordina invalidación de caché.
- **Price lookup service**: combina regla, caché, selección de proveedor, consolidación y fallback.
- **Provider clients**: adaptan los dos formatos externos a `PriceQuote`, aplican timeouts, breaker y bulkhead.
- **PostgreSQL**: catálogo persistente y restricciones de integridad.
- **Redis**: caché compartida y ventana deslizante Lua para limitar B a 5 peticiones por segundo.
- **WireMock**: stubs HTTP locales; latencia, stale, fallos y `429` configurados por mappings.

## Qué se hizo, paso a paso

### 1. Diseño y alcance

Se convirtió el PDF en decisiones de arquitectura y en [PLAN.md](../PLAN.md): Java 21/Spring Boot, React/TypeScript, PostgreSQL, Redis, WireMock, pruebas JUnit 5 y una ejecución con Compose. Se documentó explícitamente que la latencia máxima de B puede exceder 800 ms; por eso el sistema acota la espera y degrada, pero una garantía p95 requiere medir una carga representativa.

### 2. Estructura y ejecución local

Se crearon los proyectos Maven y Vite. Los Dockerfiles hacen builds multi-stage; `docker-compose.yml` conecta los cinco servicios y agrega health checks. `docker compose up --build` es el punto de entrada único. Las imágenes de aplicación son `gestion-precios-backend` y `gestion-precios-frontend`.

### 3. Persistencia y CRUD

`ProductRule` contiene UUID, SKU único, nombre, margen, proveedor preferido, estado, `updatedAt` y `@Version`. Las migraciones Flyway crean la tabla y cargan la regla demo `ABC-123` (15%, `AUTO`). Se añadieron `POST`, `GET`, `PUT` y `DELETE` bajo `/api/v1/product-rules`.

El SKU se recorta y se canonicaliza a mayúsculas en la interfaz y el backend. El repositorio busca ignorando casing para que registros antiguos como `wec-321` sigan pudiendo editarse/eliminarse y no puedan duplicarse como `WEC-321`.

### 4. Integración de precios

`GET /api/v1/prices?sku=...` primero carga la regla activa. Después consulta Redis con una identidad que incluye SKU, ID y versión de regla. Las cotizaciones se consideran frescas durante 60 segundos y se retienen hasta 15 minutos. El margen se calcula al responder:

`finalPrice = providerPrice * (1 + marginPercentage / 100)`

Para `AUTO` y `PROVIDER_A`, A es la ruta rápida. Una respuesta stale se entrega con `stale: true` y dispara un refresh asíncrono desde B cuando hay permiso. Si A falla, se hace failover síncrono a B. Para `PROVIDER_B`, B es la fuente directa. Una cotización previa dentro de la retención puede responder como `FALLBACK_CACHE`; sin fallback se devuelve `503` estructurado.

### 5. Tráfico, caché y resiliencia

- `single-flight` coalesce solicitudes concurrentes del mismo SKU/ID/versión dentro de cada instancia; una ráfaga no dispara un fetch por request.
- La clave de caché incluye `ruleId` además de SKU y `@Version`, evitando colisiones al eliminar y recrear un SKU.
- Un script Lua en Redis aplica una ventana deslizante global: máximo 5 peticiones a B en 1000 ms, sin cola de espera.
- Cada proveedor tiene circuit breaker: ventana de 10 llamadas, mínimo 10, apertura al 50% de fallos, 10 s abierto y 3 llamadas half-open.
- Bulkheads no bloqueantes: 32 llamadas simultáneas a A y 5 a B por instancia.
- Timeouts: conexión máximo 100 ms; lectura 250 ms para A y 650 ms para B. No hay reintentos de B para no duplicar consumo de cuota.
- Las llamadas de refresh usan un pool local acotado de 2–4 hilos y cola de 100.

### 6. Proveedores simulados

Los mappings de `stubs/mappings/` modelan:

- A: 100 ms en el stub y un ciclo determinista de 10 respuestas con 3 stale (30%); además perfiles específicos stale y fail.
- B: 500 ms, respuesta autoritativa, error `503`, perfil `429` y falla durante el caso `STALE-FALLBACK`.

El ciclo A es compartido por los requests genéricos del stub y se reinicia al recrear WireMock. Las pruebas usan clocks/stubs controlados; no dependen de pausas largas ni aleatoriedad.

### 7. Interfaz

La SPA muestra la tabla de reglas, formulario, estados activos, acciones por SKU y monitor. Editar enfoca el formulario; consultar muestra progreso y desplaza al monitor. Una edición invalida el resultado mostrado para evitar combinar un precio viejo con un margen nuevo. El campo SKU muestra mayúsculas inmediatamente. Los avisos de éxito desaparecen a los 4 segundos; los errores permanecen hasta una nueva acción/corrección.

### 8. Automatización y validación

La cobertura se reparte en cinco clases:

| Clase | Qué verifica |
|---|---|
| `ApiControllerTest` (9 casos) | HTTP: create/list/update/delete/prices, `201` + `Location`, validación por campo y `400/404/409/503`. |
| `PriceLookupFlowTest` (5 casos) | Cache hit, invalidación por `PUT`, expiración TTL, SKU canonicalizado y borrado legacy. |
| `PriceLookupServiceTest` (6 casos) | Cache, failover, caída total, fallback stale, rate limit agotado y 30 consultas concurrentes. |
| `ProviderCircuitBreakerTest` (1 caso) | El circuito abre tras fallos y rechaza llamadas mientras está abierto. |
| `ProviderBRateLimiterRedisTest` (1 caso) | Redis admite cinco permisos y rechaza el sexto dentro de la ventana. |

**Total: 22 pruebas, sin fallos ni omisiones** en la última ejecución. En este equipo Maven usa JDK 26 y Byte Buddy advierte que su soporte oficial llega hasta Java 24; por eso se probó con `JAVA_TOOL_OPTIONS=-Dnet.bytebuddy.experimental=true`. La imagen de ejecución utiliza Java 21. El build TypeScript y `npm audit --omit=dev` también pasaron.

## Mapa de archivos

| Ruta | Responsabilidad |
|---|---|
| `backend/pom.xml` | Java 21, Spring Boot, persistencia, Redis, Resilience4j y dependencias de test. |
| `backend/src/main/java/.../rules/` | Entidad, DTOs, enum, repositorio, servicio y controller de reglas. |
| `backend/src/main/java/.../pricing/` | Contratos de precios, cache, lookup, clients A/B, rate limiter y controller. |
| `backend/src/main/java/.../api/` | Esquema uniforme de errores y exception advice. |
| `backend/src/main/java/.../config/` | `Clock` y executor de refresh. |
| `backend/src/main/resources/application.yml` | Puertos, conexiones, timeouts, health y circuit breakers. |
| `backend/src/main/resources/db/migration/` | `V1` schema PostgreSQL; `V2` regla de demostración. |
| `backend/src/test/java/.../api/` | Pruebas de contratos HTTP con MockMvc. |
| `backend/src/test/java/.../pricing/` | Pruebas de cache, fallos, resiliencia, rate limit y concurrencia. |
| `frontend/src/App.tsx` | Flujos de usuario y composición de la pantalla. |
| `frontend/src/types.ts` | Tipos, cliente HTTP y normalización de errores en la SPA. |
| `frontend/src/styles.css` | Sistema visual responsive, estado reducido de movimiento y estilos de feedback. |
| `frontend/Dockerfile`, `frontend/nginx.conf` | Build React y reverse proxy a la API. |
| `backend/Dockerfile` | Build Maven y runtime Java 21. |
| `docker-compose.yml` | Topología local: PostgreSQL, Redis, WireMock, backend y frontend. |
| `stubs/mappings/` | Escenarios HTTP de A/B. |
| `README.md` | Arquitectura, API, decisiones, configuración y ejecución. |
| `PLAN.md` | Plan inicial y criterios de aceptación. |

## Contratos de API

| Método | Ruta | Uso |
|---|---|---|
| `POST` | `/api/v1/product-rules` | Alta, SKU único, validación y canonicalización. |
| `GET` | `/api/v1/product-rules` | Catálogo ordenado por SKU. |
| `PUT` | `/api/v1/product-rules/{sku}` | Actualiza atributos comerciales y elimina la clave vieja de cache. |
| `DELETE` | `/api/v1/product-rules/{sku}` | Elimina la regla e invalida la clave correspondiente. |
| `GET` | `/api/v1/prices?sku={sku}` | Consolida proveedor, margen, precio, origen, stale y latencia. |

## Límites y decisiones para explicar en la defensa

1. El `single-flight` vive en memoria por instancia; Redis comparte cache y cuota, pero no la promesa en curso. Con varias réplicas puede haber hasta un fetch simultáneo por réplica para un SKU frío.
2. El test de ráfaga cubre 30 requests concurrentes del mismo SKU y verificó un solo fetch externo; no sustituye un benchmark mixto de muchos SKU, cache fría y tráfico sostenido.
3. B puede tardar 900 ms según el enunciado; el timeout de lectura elegido es 650 ms. Se prioriza respuesta acotada y stale fallback sobre esperar a B indefinidamente.
4. PostgreSQL/Redis de Compose usan credenciales demo; no deben reutilizarse en producción.
5. Para demostrar el stale fallback, crear `STALE-FALLBACK`, consultar una vez para llenar cache y consultar de nuevo: la segunda respuesta debe indicar `FALLBACK_CACHE` mientras ambos mappings fallan.

## Ejecución rápida

```sh
docker compose up --build
```

Interfaz en `http://localhost:3000`; API en `http://localhost:8080`; health en `http://localhost:8080/actuator/health`. Para parar conservando datos: `docker compose down`. `docker compose down -v` elimina también el volumen PostgreSQL.
