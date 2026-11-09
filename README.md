# Precio Claro

Aplicación de gestión de reglas comerciales y consulta consolidada de precios. El backend administra reglas en PostgreSQL, integra dos proveedores simulados, mantiene cotizaciones en Redis y expone una SPA React.

## Arquitectura

```mermaid
flowchart LR
    U[Usuario] --> W[SPA React / Nginx]
    W -->|REST /api/v1| API[Spring Boot]
    API -->|CRUD de reglas| DB[(PostgreSQL)]
    API -->|Cotización por SKU + versión| C[(Redis: caché y rate limit)]
    API -->|GET /a/prices/{sku}| A[WireMock · Proveedor A]
    API -->|GET /b/prices/{sku}| B[WireMock · Proveedor B]
    A --> API
    B --> API
    API --> W
```

## Tecnologías

- Java 21, Spring Boot 3.4, Maven, Spring Web, Validation, Data JPA y Resilience4j.
- PostgreSQL 16 con Flyway; Redis 7 para caché y límite distribuido de B.
- React 19, TypeScript 5 y Vite; Nginx sirve los recursos y hace proxy de `/api`.
- WireMock 3.13 simula los dos contratos externos.

## Ejecución

Requisito: Docker Compose v2.

```sh
docker compose up --build
```

- Aplicación: http://localhost:3000
- API: http://localhost:8080/api/v1
- Salud: http://localhost:8080/actuator/health
- WireMock admin: http://localhost:8082/__admin
- Regla demo precargada: `ABC-123`, margen 15%, modo `AUTO`.

Para detener los contenedores: `docker compose down`. Para borrar también PostgreSQL: `docker compose down -v`.

## Contratos de API

| Método | Ruta | Resultado |
|---|---|---|
| `POST` | `/api/v1/product-rules` | Crea una regla; SKU duplicado devuelve `409`. |
| `GET` | `/api/v1/product-rules` | Lista reglas por SKU. |
| `PUT` | `/api/v1/product-rules/{sku}` | Actualiza nombre, margen, proveedor y estado; invalida caché. |
| `DELETE` | `/api/v1/product-rules/{sku}` | Elimina la regla e invalida caché. |
| `GET` | `/api/v1/prices?sku={sku}` | Devuelve cotización, margen, precio final, fuente, stale y latencia. |

Ejemplo de regla:

```json
{
  "sku": "ABC-123",
  "name": "Producto de demostración",
  "marginPercentage": 15,
  "preferredProvider": "AUTO",
  "active": true
}
```

Los errores usan `{ "timestamp", "status", "error", "message", "fields" }`. Un SKU inexistente o inactivo produce `404`; validación `400`; duplicado `409`; indisponibilidad sin fallback `503`.

## Uso de la SPA

- Usa **Nueva regla** para crear un SKU; se recortan espacios y se convierte a mayúsculas al escribir y en la API. **Editar** abre el formulario, desplaza la vista hasta él y enfoca el primer campo. **Guardar regla** aplica los cambios y refresca el catálogo; **Eliminar** pide confirmación.
- El botón de consulta en la fila de un SKU activo solicita su precio y desplaza la vista al monitor. Mientras espera, el monitor indica qué SKU consulta; luego presenta el precio del proveedor, margen, total, fuente, frescura y latencia. En móvil, el monitor queda al inicio del área visible.
- Las reglas inactivas aparecen con estado **Inactiva** y no permiten consulta. Los errores de validación, SKU inactivo y falta de proveedores se muestran en alertas.
- Los avisos de éxito por crear, editar o eliminar se ocultan automáticamente después de 4 segundos; los errores permanecen visibles hasta corregir o iniciar otra acción.

## Estrategia de consulta

1. Se valida la regla activa en PostgreSQL antes de consultar caché o proveedores.
2. Redis conserva la respuesta normalizada por `SKU + versión optimista de la regla`. Una cotización se considera fresca durante 60 segundos y se conserva hasta 15 minutos para fallback. El margen se calcula con la regla actual, no se almacena dentro de la cotización.
3. En `AUTO` y `PROVIDER_A`, A se consulta primero. Un resultado fresco se devuelve inmediatamente. Un dato de A con más de 15 minutos se devuelve marcado como stale y provoca una actualización asíncrona desde B si se obtiene permiso de tasa.
4. Si A falla, se intenta B sin encolar y con timeout de 650 ms. En `PROVIDER_B`, B es la fuente directa. Una cotización stale utilizable sirve como `FALLBACK_CACHE` ante error, circuito abierto o falta de permiso.
5. Si no hay fallback disponible, la API responde `503` controlado. El precio final se calcula como `providerPrice * (1 + marginPercentage / 100)` y se redondea a dos decimales.

La respuesta típica usa la caché o A (stub de 100 ms). B demora 500 ms en el stub y puede demorar hasta 900 ms según el enunciado. El cliente limita conexión a 100 ms y lectura a 650 ms; esto acota llamadas lentas y deja margen bajo los 800 ms para el camino habitual, aunque no constituye una garantía p95 ante saturación de DB/Redis, pausas de JVM o degradación de red. No se encolan solicitudes excedentes y se aprovecha fallback stale. El SLA debe medirse con carga representativa y una mezcla de tráfico acordada.

## Caché y resiliencia

- Clave: `price:v1:{sku}:{ruleId}:{ruleVersion}`; TTL de retención: 15 minutos; frescura de caché: 60 segundos.
- Cada actualización incrementa `@Version`. La clave `SKU + id de regla + versión` evita colisiones incluso al eliminar y recrear un SKU. Se invalida la clave anterior después de la escritura.
- Un `single-flight` local por `SKU + id de regla + versión` hace que las solicitudes simultáneas de la misma instancia compartan el fetch externo; otras instancias siguen protegidas por el rate limit distribuido, pero no comparten ese fetch.
- El limitador de B ejecuta una ventana deslizante Lua atómica en Redis: máximo 5 peticiones en cualquier ventana de 1000 ms, compartida entre instancias. Si no hay permiso, no se llama a B ni se encola la consulta.
- Circuit breaker independiente por proveedor: ventana de 10 llamadas, mínimo 10, apertura al 50% de fallos, espera de 10 segundos y 3 llamadas permitidas en half-open.
- Timeouts: conexión máximo 100 ms; lectura 250 ms para A y 650 ms para B. No hay reintentos automáticos de B: evitan consumo duplicado de cuota y latencia adicional.
- Bulkheads con semáforos no bloqueantes: 32 llamadas simultáneas a A y 5 a B por instancia. Sin permiso, la integración degrada a B o a caché/error controlado en lugar de hacer cola.
- La actualización stale se ejecuta en un pool acotado de 2-4 hilos con cola de 100 tareas.

## Stubs disponibles

Los stubs están en `stubs/mappings`; A responde en `/a/prices/{sku}` con 100 ms y un ciclo determinista de diez estados (tres stale, 30%); B responde en `/b/prices/{sku}` con 500 ms. Perfiles preparados: `FAIL-A` falla A para probar failover; `FAIL-ALL` falla ambos y produce `503`; `STALE-1` siempre devuelve stale; `STALE-FALLBACK` devuelve stale en la primera llamada, luego A y B fallan para demostrar `FALLBACK_CACHE`; `RATE-LIMIT` fuerza un `429` de B cuando la regla prefiere `PROVIDER_B`. Para usarlos, crea reglas con esos SKUs desde la interfaz. El ciclo genérico de A se comparte entre llamadas al stub y se reinicia al recrear WireMock.

WireMock acepta cambios de mappings mediante su API admin. La URL externa local es `http://localhost:8082`; desde backend Compose se usa `http://wiremock:8080`.

## Pruebas y desarrollo

```sh
mvn -f backend/pom.xml test
npm --prefix frontend install
npm --prefix frontend run build
```

Las pruebas JUnit usan reloj controlado, clientes/proveedores simulados y executor directo; no requieren `Thread.sleep`. La prueba del script Lua conecta al Redis local en `localhost:6379`; inicia `docker compose up -d redis` antes de Maven para ejecutarla, y se omite de forma explícita si Redis no está disponible.

| Prueba | Escenario cubierto |
|---|---|
| `PriceLookupFlowTest.servesRepeatedRequestFromCacheAndRecalculatesAfterRuleUpdate` | Segunda consulta servida desde caché sin llamar proveedores; tras `PUT` del margen se invalida y se recalcula. |
| `PriceLookupFlowTest.queriesProviderAgainOnceCacheTtlExpires` | Al avanzar el reloj más allá de 60 s se vuelve a consultar al proveedor. |
| `PriceLookupFlowTest` | Normaliza SKU en create/lookup/update y elimina reglas legacy con casing antiguo, invalidando su caché. |
| `ApiControllerTest` | MockMvc verifica `201` + `Location`, listado, `PUT`, `DELETE 204`, contrato de precios y errores `400/404/409/503`. |
| `PriceLookupServiceTest.failsOverToProviderBWhenProviderAIsDown` | Failover de A a B. |
| `PriceLookupServiceTest.returnsControlledUnavailableWhenBothProvidersFailWithoutFallback` | Caída total: excepción de dominio traducida a `503` controlado. |
| `PriceLookupServiceTest.fallsBackToStaleCacheWhenProviderBRateLimitIsReached` | Sin permiso de rate limit no se llama a B y se responde `FALLBACK_CACHE`. |
| `PriceLookupServiceTest.coalescesConcurrentMissesForTheSameRuleVersion` | Treinta solicitudes concurrentes del mismo SKU/regla comparten un solo fetch externo, usando barreras y latches. |
| `PriceLookupServiceTest.allowsRecentlyReceivedQuoteAsStaleFallback` | Permite usar el último stale recibido dentro de la retención de 15 minutos y lo devuelve marcado como stale. |
| `ProviderCircuitBreakerTest` | Tras 10 fallos el circuito queda `OPEN` y rechaza sin invocar al proveedor. |
| `ProviderBRateLimiterRedisTest` | Redis real admite cinco permisos y rechaza el sexto dentro de la ventana móvil. |

El circuit breaker se aplica explícitamente con `CircuitBreakerRegistry` en cada cliente, usando la configuración de `application.yml`. Con JDK 21, el comando Maven anterior es suficiente. En una máquina con JDK 26 y Byte Buddy anterior a soporte oficial para esa versión puede ejecutarse con `JAVA_TOOL_OPTIONS=-Dnet.bytebuddy.experimental=true` como compatibilidad local.

Para desarrollo sin contenedores de aplicación, iniciar PostgreSQL/Redis/WireMock con Compose, ejecutar `mvn -f backend/pom.xml spring-boot:run` y `npm --prefix frontend run dev`. Vite reenvía `/api` a `localhost:8080`.

## Configuración

Las propiedades externas aceptan `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `PROVIDER_A_URL`, `PROVIDER_B_URL`, `PROVIDER_A_TIMEOUT_MS` y `PROVIDER_B_TIMEOUT_MS`. Compose usa credenciales de demostración no aptas para producción. No se requieren secretos para ejecutar el proyecto.

## Decisiones y límites

- Se utiliza PostgreSQL para persistencia relacional y restricciones reales de unicidad; Redis permite compartir caché y rate limit si hay más de una instancia de backend.
- El fallback stale puede tener timestamp del proveedor anterior a 15 minutos, pero solo se conserva durante 15 minutos desde su recepción y siempre se marca `stale: true`.
- El rate limit distribuido protege a B incluso con múltiples réplicas, pero la cola de refresh es local a cada instancia.
- Las respuestas de WireMock son deterministas para que las verificaciones sean reproducibles; el porcentaje aleatorio de stale del proveedor A se representa con un perfil stale explícito.