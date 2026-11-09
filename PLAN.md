# Plan de trabajo: Sistema de Gestión y Consulta Consolidada de Precios

## Objetivo

Construir una aplicación de extremo a extremo para administrar reglas de producto y consultar precios de dos proveedores simulados, con caché, tolerancia a fallos, control de tasa y una interfaz web. El plan cubre backend, frontend, pruebas, ejecución local y documentación, usando las tecnologías solicitadas en la prueba.

## Decisiones iniciales

- **Backend:** Java 21, Spring Boot 3, Maven y API REST bajo `/api/v1`.
- **Persistencia:** PostgreSQL con Spring Data JPA y migraciones Flyway.
- **Caché compartida:** Redis. Se conserva `fetchedAt` y se distinguen valores frescos y utilizables como fallback.
- **Frontend:** React con TypeScript y Vite; componentes pequeños y estado explícito para carga, éxito y error.
- **Proveedores simulados:** WireMock local, configurado con respuestas deterministas y escenarios de latencia, datos stale, errores y HTTP 429.
- **Integración HTTP:** cliente HTTP de Spring con timeout por proveedor.
- **Resiliencia:** Circuit Breaker de Resilience4j por proveedor. Evitar reintentos automáticos de B para no exceder su cuota ni consumir el presupuesto de latencia.
- **Límite de B:** ventana deslizante compartida en Redis, máximo 5 solicitudes en cualquier periodo de 1 segundo; no encolar esperando un permiso. Si no hay permiso, usar caché stale si existe o devolver un error controlado.
- **Ejecución:** `docker compose up --build` levanta frontend, backend, PostgreSQL, Redis y stubs.

Las versiones exactas se fijarán al crear el proyecto y se documentarán en los manifiestos. No se añadirán librerías que no tengan un uso definido.

## Estrategia funcional

1. Cada consulta valida la regla local y comprueba que esté activa antes de consultar Redis o a los proveedores.
2. Una entrada fresca de caché responde sin llamadas externas. La clave incorpora SKU, ID y versión de la regla; el margen se aplica con la regla actual. Las consultas simultáneas de la misma regla comparten el fetch externo dentro de la instancia.
3. En modo `AUTO`, consultar primero A. Si entrega un dato fresco, responder. Si entrega un dato stale, responderlo marcado como stale y actualizar desde B en segundo plano cuando el circuito y el límite de tasa lo permitan.
4. Si A falla y no hay una respuesta fresca, intentar B de forma síncrona con timeout acotado para realizar failover. Para `PROVIDER_B`, consultar B directamente, reutilizando caché fresca cuando esté disponible.
5. En fallo, circuito abierto o falta de permiso para B, responder con caché stale disponible (`FALLBACK_CACHE`). Sin una cotización previa, devolver error HTTP controlado con mensaje accionable, nunca una excepción cruda.
6. Aplicar `finalPrice = providerPrice * (1 + marginPercentage / 100)` y devolver fuente, stale y latencia observada.

La estrategia prioriza una respuesta rápida en el camino frecuente y evita esperar a B en una actualización stale. El objetivo p95 depende de la proporción de caché/A, del tráfico y de los timeouts; no puede garantizarse en el camino síncrono de B cuando su latencia llega a 900 ms. El README debe documentar este límite y la degradación utilizada.

## Fases y estimación (24 horas)

| Fase | Tiempo | Trabajo y entregable |
|---|---:|---|
| 1. Diseño y contratos | 1.5 h | Confirmar decisiones, modelo `ProductRule`, DTOs, errores HTTP y diagrama de secuencia. Entregable: contratos y decisiones volcados al README. |
| 2. Estructura y entorno | 2 h | Crear proyectos Spring Boot y React/TypeScript; configurar Docker Compose, PostgreSQL, Redis y WireMock. Entregable: ecosistema inicia con un comando y health checks disponibles. |
| 3. CRUD backend | 4 h | Entidad, validaciones, migración, repositorio, servicio y endpoints de crear/listar/editar/eliminar o desactivar. Manejar SKU duplicado y errores de validación. |
| 4. Middleware de precios | 6 h | Adaptadores A/B, normalización de respuestas, estrategia de selección/failover, caché e invalidación, deduplicación de consultas simultáneas por SKU, circuit breakers, token bucket B y fallback stale. |
| 5. Frontend | 4 h | Tabla de reglas, formulario crear/editar, acción de eliminación/desactivación, consulta de precio y visualización del desglose, fuente, stale, latencia y errores. |
| 6. Pruebas | 3 h | Pruebas JUnit 5 deterministas de CRUD, caché, invalidación, TTL, failover, caída total, circuit breaker y límite de tasa; pruebas de integración con stubs controlados. |
| 7. Ensamble y documentación | 2.5 h | Probar el flujo en Compose, completar README, diagrama Mermaid, configuración y pasos de ejecución. |
| 8. Revisión final | 1 h | Ejecutar pruebas y build, revisar validaciones y manejo de errores, verificar que no haya dependencias o archivos de prueba innecesarios. |
| **Total** | **24 h** | |

## Detalle de implementación

### Backend

- `ProductRule`: `id`, `sku` único, `name`, `marginPercentage`, `preferredProvider`, `active`, `updatedAt`.
- Validar SKU/nombre no vacíos, margen dentro de un rango documentado y proveedor dentro del enum permitido.
- Separar controlador, servicio de aplicación, persistencia y adaptadores externos; usar DTOs para no exponer entidades JPA.
- Endpoints requeridos:
  - `POST /api/v1/product-rules`
  - `GET /api/v1/product-rules`
  - `PUT /api/v1/product-rules/{sku}`
  - `DELETE /api/v1/product-rules/{sku}`
  - `GET /api/v1/prices?sku={sku}`
- Precios: representar proveedor y respuesta externa en tipos normalizados internos. Convertir `timestamp` epoch del proveedor B a `Instant` y determinar stale con reloj inyectable y umbral de 15 minutos.
- Resiliencia inicial a validar: circuit breaker con ventana de 10 llamadas, apertura desde 50% de fallos, espera abierta de 10 segundos y 3 llamadas de prueba en half-open. Usar `Clock` inyectable y configuración explícita para pruebas deterministas.
- Bulkheads iniciales no bloqueantes: máximo 32 llamadas concurrentes a A y 5 a B por instancia.
- Timeouts iniciales: conexión máximo 100 ms; lectura A 250 ms y B 650 ms para llamadas síncronas. Medir el p95 bajo carga y documentar las condiciones del SLA.
- En edición/eliminación, cambiar la versión de la regla e invalidar la clave actual de caché. La versión en la clave evita que una consulta concurrente antigua vuelva a ser servida bajo la regla nueva.
- Devolver errores consistentes, por ejemplo `404` para SKU inexistente/inactivo, `409` para duplicado y `503` si no hay cotización ni fallback disponible.

### Stubs externos

- WireMock expondrá contratos propios para A y B y será parte del Compose.
- Los escenarios de pruebas usarán respuestas fijas para controlar frescura, latencia y fallos; el modo demo podrá activar latencias/porcentajes configurables sin aleatoriedad en las pruebas.
- No depender de esperas largas ni de la aleatoriedad para validar comportamiento.

### Frontend

- Una vista principal con tabla de reglas y formulario modal o panel accesible para alta/edición.
- Permitir consultar precio desde una regla y mostrar base, margen, total, moneda, proveedor/fuente, stale y latencia.
- Presentar estados de carga y mensajes distintos para SKU inexistente/inactivo, validación, indisponibilidad de proveedores y fallback stale.
- Mantener la integración HTTP en un módulo cliente y las reglas de presentación en componentes; evitar lógica de negocio duplicada en la UI.

## Pruebas de aceptación

- Una regla válida se crea y aparece en el listado; un SKU duplicado retorna `409`.
- No se puede consultar una regla inexistente o inactiva; se devuelve el error controlado acordado.
- Segunda consulta dentro del TTL usa caché y no invoca proveedores.
- Un `PUT` cambia el margen, invalida la cotización cacheada y el precio final refleja el margen nuevo; `DELETE` también invalida.
- Después del TTL se consulta nuevamente al proveedor.
- Si A falla, B puede responder dentro del timeout configurado; si ambos fallan, se entrega fallback stale o un `503` controlado.
- Una respuesta de A con antigüedad mayor a 15 minutos se marca stale y programa actualización de B según la estrategia.
- Las llamadas a B no superan 5 RPS; cuando se agota el permiso no se forma una cola que afecte el p95.
- El Circuit Breaker abre con fallos simulados y permite una transición half-open comprobable sin `Thread.sleep` largo.
- La SPA cubre CRUD y consulta, muestra fuente/frescura/latencia y representa los errores de API sin quedar en estado de carga.
- `docker compose up --build` inicia todo el ecosistema y el README permite reproducir las verificaciones.

## Estándares de legibilidad y mantenimiento

- Aplicar la skill disponible `readable-code`: resolver la necesidad más simple que cumpla el contrato, reutilizar mecanismos existentes y evitar abstracciones especulativas.
- Nombrar clases y métodos por su responsabilidad; mantener métodos pequeños con flujo explícito y tipos de dominio donde aclaren contratos.
- Mantener controladores delgados, lógica de decisión en servicios y comunicación externa detrás de adaptadores.
- No duplicar el cálculo del margen, la política stale ni el mapeo de errores entre backend y frontend.
- No añadir comentarios que repitan el código. Documentar decisiones, límites, configuración y comandos en el README.
- Probar tiempo con `Clock` inyectable y stubs configurados; no usar `Thread.sleep` prolongado en pruebas.
- Formatear y validar cambios con los comandos del proyecto, documentados en el README.

## README requerido para la entrega

- Resumen y arquitectura con diagrama Mermaid desde SPA hasta API, base de datos, Redis y proveedores.
- Stack y versiones; decisiones de caché, frescura, consulta/consolidación, invalidación, resiliencia y rate limit, con valores concretos.
- Limitaciones del p95 ante el proveedor B y comportamiento degradado.
- Requisitos previos, comando único de arranque, URLs/puertos, datos de prueba y configuración.
- Comandos para pruebas/build y una guía breve de verificación manual de los flujos principales.
- Variables de entorno de ejemplo sin secretos reales.
- Declarar el uso de herramientas de IA conforme a las reglas de la prueba; no afirmar autonomía o autoría que no corresponda.

## Criterio de terminado

La entrega está lista cuando el Compose levanta la SPA, API, PostgreSQL, Redis y WireMock; CRUD y consulta funcionan de extremo a extremo; las pruebas de aceptación pasan; las fallas se presentan de forma controlada; y el README permite ejecutar y entender el sistema sin contexto adicional.
