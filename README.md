# Precio Claro

Aplicación de gestión de reglas comerciales y consulta consolidada de precios. El backend administra reglas en PostgreSQL, integra dos proveedores simulados, mantiene cotizaciones en Redis y expone una SPA React.

## Arquitectura

![Diagrama de arquitectura de Precio Claro](docs/diagramas/arquitectura.svg)

El gráfico también está disponible como escena editable de Excalidraw en [docs/diagramas/arquitectura.excalidraw](docs/diagramas/arquitectura.excalidraw). Consulta la [guía integral del proyecto](docs/GUIA_DEL_PROYECTO.md) para conocer tecnologías, arquitectura, lógica de negocio, API y operación; el [reporte técnico](docs/REPORTE_IMPLEMENTACION.md) detalla la implementación y la evidencia de pruebas.

### Flujo end-to-end

El diagrama distingue las operaciones CRUD de la consulta de precios. En el camino de consulta, A y B no se invocan en paralelo: B solo se consulta directamente cuando la regla lo prefiere, después de un fallo de A o para refrescar una cotización stale en segundo plano.

```mermaid
flowchart LR
    U[Usuario] --> FE[SPA React]
    FE -->|HTTP /api| NG[Nginx]
    NG --> API[Spring Boot API]
    API -->|POST / GET / PUT / DELETE reglas| CRUD[ProductRuleService]
    CRUD -->|Persistencia e integridad| PG[(PostgreSQL)]
    API -->|GET /prices?sku| LOOKUP[PriceLookupService]
    LOOKUP -->|Validar regla activa| PG
    LOOKUP --> RC[(Redis: cotizaciones)]
    RC --> FRESH{Cotización fresca?}
    FRESH -->|Sí| MARGIN[Aplicar margen de regla]
    FRESH -->|No| PREF{Proveedor preferido}
    PREF -->|AUTO o A| A[Proveedor A]
    A -->|Respuesta| CHECK{Cotización stale?}
    CHECK -->|No| SAVE[Guardar cotización en Redis]
    CHECK -->|Sí| SAVE
    CHECK -->|Sí, además| REFRESH[Refresh asíncrono]
    REFRESH --> LIMIT_REFRESH[Rate limit global en Redis]
    LIMIT_REFRESH -->|Permiso| B_REFRESH[Proveedor B para refresh]
    B_REFRESH -->|Actualizar cotización, sin esperar| RC
    PREF -->|B directo| LIMIT_SYNC[Rate limit global en Redis]
    A -->|Error / breaker abierto| LIMIT_SYNC
    LIMIT_SYNC -->|Permiso| B_SYNC[Proveedor B para consulta]
    LIMIT_SYNC -->|Sin permiso| FALLBACK{Fallback stale disponible?}
    B_SYNC -->|Respuesta| SAVE
    B_SYNC -->|Error / timeout / breaker abierto| FALLBACK
    FALLBACK -->|Sí| MARGIN
    FALLBACK -->|No| UNAVAILABLE[503 controlado]
    SAVE --> MARGIN
    MARGIN --> RESPONSE[Respuesta: base, margen, total, fuente, stale y latencia]
    RESPONSE --> FE
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

1. Se normaliza el SKU y se carga su regla desde PostgreSQL. Se rechazan con `404` las reglas inexistentes o inactivas antes de leer caché o invocar proveedores.
2. Se busca en Redis una cotización para la identidad de esa regla. Si está fresca, se reutiliza sin llamada externa.
3. En `AUTO` o `PROVIDER_A`, se invoca A primero. Si A responde con una cotización de antigüedad no superior a 15 minutos, se usa como fuente de la respuesta. Si A responde con una cotización más antigua, se devuelve marcada `stale: true` y se intenta actualizar con B en segundo plano, sujeto al rate limit. Este caso no espera la actualización de B.
4. B se invoca de forma síncrona si la regla prefiere `PROVIDER_B`, o si A falla (incluidos timeout, circuito abierto o saturación de su bulkhead). La llamada a B consume un permiso del limitador antes de tocar al proveedor; no se espera en una cola.
5. Si B falla o no hay permiso, se responde con una cotización de fallback retenida, marcada como `FALLBACK_CACHE` y stale. Si no hay ninguna disponible, se devuelve `503`.
6. La cotización elegida se normaliza al mismo modelo independientemente del proveedor. El backend le aplica el margen vigente de la regla: `providerPrice * (1 + marginPercentage / 100)`, con redondeo a dos decimales. No se promedian ni suman cotizaciones A/B: cuando B entra en la ruta es failover, fuente preferida o actualización de respaldo, no una combinación aritmética. La respuesta incluye precio base, margen, total, moneda, fuente, frescura y latencia.

La estrategia favorece la frescura y el tiempo de respuesta: cache fresca evita llamadas externas; A es el camino primario rápido para `AUTO` y `PROVIDER_A`; B se reserva para preferencia explícita, failover o refresh no bloqueante. Los stubs simulan aproximadamente 100 ms para A y 500 ms para B; el escenario de B puede llegar a 900 ms según el requisito original.

El diseño busca mantener el camino habitual —caché o A— por debajo de 800 ms y limita el tiempo de lectura de A a 250 ms. Para B, el timeout de conexión es de 100 ms y el de lectura, 650 ms; al no reintentar ni encolar, se limita la espera del proveedor. El rate limit evita que una ráfaga exceda la cuota: cuando se agota, se usa fallback stale o se responde `503`, no se acumula latencia esperando una ventana. Sin embargo, el timeout no garantiza por sí solo un p95 menor de 800 ms: B puede consumir hasta 650 ms de lectura, además del tiempo de DB, Redis, procesamiento y red. La meta p95 debe comprobarse con pruebas de carga representativas y una mezcla de tráfico acordada.

## Caché y resiliencia

- Se cachea en Redis la cotización normalizada del proveedor (precio base, moneda, proveedor y timestamp de cotización), junto con el momento local en que se guardó. No se cachean el margen ni el precio final: ambos dependen de la regla vigente y se calculan al responder.
- Clave: `price:v1:{sku}:{ruleId}:{ruleVersion}`. La retención en Redis es de 15 minutos. La entrada es fresca por hasta 60 segundos desde su almacenamiento, siempre que la cotización del proveedor tampoco tenga más de 15 minutos de antigüedad.
- `@Version` de JPA aumenta al actualizar la regla. Después del `PUT`, se invalida la clave de la versión anterior; las siguientes consultas usan la nueva versión y no pueden encontrar el dato bajo la clave anterior. En `DELETE` se invalida la clave vigente; si luego se crea el mismo SKU, el nuevo `ruleId` también lo aísla de cualquier clave anterior. Así, incluso ante una carrera con una solicitud concurrente que ya estaba en curso, su dato queda asociado a la versión vieja y no es elegible para la regla editada.
- Un `single-flight` local por `SKU + id de regla + versión` hace que las solicitudes simultáneas de la misma instancia compartan el fetch externo; otras instancias siguen protegidas por el rate limit distribuido, pero no comparten ese fetch.
- El limitador de B ejecuta una ventana deslizante Lua atómica en Redis: máximo 5 peticiones en cualquier ventana de 1000 ms, compartida entre instancias. Cada intento a B —incluidos failover, preferencia directa y refresh en segundo plano— requiere permiso. Si no lo obtiene, no se llama a B ni se encola la consulta; se usa fallback stale si está disponible o se devuelve `503`.
- Circuit breaker independiente por proveedor: ventana de 10 llamadas, mínimo 10, apertura al 50% de fallos, espera de 10 segundos y 3 llamadas permitidas en half-open.
- Timeouts: conexión máximo 100 ms; lectura 250 ms para A y 650 ms para B. No hay reintentos automáticos de B: evitan consumo duplicado de cuota y latencia adicional.
- Bulkheads con semáforos no bloqueantes: 32 llamadas simultáneas a A y 5 a B por instancia. Sin permiso, la integración degrada a B o a caché/error controlado en lugar de hacer cola.
- La actualización stale se ejecuta en un pool acotado de 2-4 hilos con cola de 100 tareas.

## Stubs disponibles

Los stubs están en `stubs/mappings`; A responde en `/a/prices/{sku}` con 100 ms y un ciclo determinista de diez estados (tres stale, 30%); las respuestas frescas usan la hora actual para no envejecer con el tiempo, mientras los estados stale conservan timestamps antiguos intencionalmente. B responde en `/b/prices/{sku}` con 500 ms y su timestamp fresco también se genera dinámicamente. Perfiles preparados: `FAIL-A` falla A para probar failover; `FAIL-ALL` falla ambos y produce `503`; `STALE-1` siempre devuelve stale; `STALE-FALLBACK` devuelve stale en la primera llamada, luego A y B fallan para demostrar `FALLBACK_CACHE`; `RATE-LIMIT` fuerza un `429` de B cuando la regla prefiere `PROVIDER_B`. Para usarlos, crea reglas con esos SKUs desde la interfaz. El ciclo genérico de A se comparte entre llamadas al stub y se reinicia al recrear WireMock.

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