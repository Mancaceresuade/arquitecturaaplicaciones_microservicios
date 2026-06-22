# Parcial 2 — Tema 2
### Arquitectura de Aplicaciones · UADE · Clases 8–14

**Parte práctica: implementar `cliente-service` con RabbitMQ**

> El agente de IA (Cursor, Claude Code, Copilot, etc.) **está permitido y es parte del examen**.
> Lo que se evalúa es si podés dirigirlo correctamente y entender lo que produce.

---

## Parte 1 — Práctica: implementar `cliente-service`

### Contexto

El ecosistema base ya tiene corriendo:

| Servicio | Puerto |
|----------|--------|
| `eureka-server` | 8761 |
| `config-server` | 8888 |
| `auth-service` | 8083 |
| `api-gateway` | 8080 |
| RabbitMQ Broker | 5672 (AMQP) / 15672 (UI) |

Tu tarea es implementar el módulo **`cliente-service`** dentro del proyecto que ya tenés clonado.

### Qué tenés que construir

Un microservicio Spring Boot 3 / Java 21 que:

1. Se registra en Eureka con `spring.application.name=cliente-service` en el puerto **8085**.
2. Expone bajo `/api/clientes/**` las rutas protegidas con JWT (HS384, mismo secret del ecosistema).
3. Persiste en **H2 en memoria**. El modelo `Cliente` tiene:
   - `id` → Long, generado por H2 (solo lectura; si llega en el body de un POST se ignora)
   - `nombre` → String, obligatorio, no vacío, máx. 200 caracteres
   - `telefono` → String, obligatorio, no vacío, máx. 30 caracteres

4. Implementa el flujo RabbitMQ **como publicador** sobre el exchange `cliente.exchange` (tipo **topic**):
   - **POST /api/clientes** → persiste → publica con routing key `cliente.created`
   - **PUT /api/clientes/{id}** → actualiza → publica con routing key `cliente.updated`
   - **DELETE /api/clientes/{id}** → borra → publica con routing key `cliente.deleted`

   No se exige implementar un consumidor: alcanza con publicar después de confirmar la persistencia.

5. El `api-gateway` debe enrutar `Path=/api/clientes/**` → `lb://cliente-service`.

### Contrato HTTP

```
POST   /api/clientes        → 201 Created + {id, nombre, telefono}
GET    /api/clientes        → 200 + array (puede ser [])
GET    /api/clientes/{id}   → 200 o 404
PUT    /api/clientes/{id}   → 200 o 404
DELETE /api/clientes/{id}   → 204 o 404
```
Validación fallida → **400**. Sin JWT → **401**.

### Contrato RabbitMQ (cumplimiento estricto)

| Elemento | Valor obligatorio |
|----------|-------------------|
| Exchange | `cliente.exchange` |
| Tipo de exchange | **topic** |
| Routing key (POST) | `cliente.created` |
| Routing key (PUT) | `cliente.updated` |
| Routing key (DELETE) | `cliente.deleted` |
| Content-Type | `application/json` (UTF-8) |

El **cuerpo** de cada mensaje es un objeto JSON con al menos:

| Campo | Tipo | Reglas |
|-------|------|--------|
| `eventType` | string | `CLIENTE_CREATED`, `CLIENTE_UPDATED` o `CLIENTE_DELETED` según la operación |
| `clienteId` | number | `id` del cliente afectado (Long) |
| `nombre` | string | Valor del nombre tras la operación (en `DELETED`, el último valor conocido) |
| `telefono` | string | Mismo criterio que `nombre` |
| `occurredAt` | string | Instante en ISO-8601 (por ejemplo con offset `Z`) |

> **Orden sugerido**: publicar **después** de confirmar la persistencia, para evitar eventos huérfanos si falla la base.

### Cómo verificar

```bash
# Configurar tu legajo (reemplazá 12345 por el tuyo)
set EXAM_STUDENT_ID=12345           # Windows CMD
# export EXAM_STUDENT_ID=12345      # Git Bash / Linux

# Correr los tests de integración
mvn -pl exam-cliente-integration-tests verify
```

**Aprobás esta parte si todos los tests quedan en verde.**
El reporte queda en `exam-cliente-integration-tests/target/exam-report/resultado-examen.json`.

> El reporte incluye tu **legajo** (`examStudentId`, tomado de `EXAM_STUDENT_ID`) y un
> **indicador de la máquina** (`examMachineId` = hostname, y `machineFingerprint` = hash
> corto de la PC). Sirven solo como referencia de quién y dónde se corrió el test; no es
> un mecanismo de seguridad. Asegurate de setear `EXAM_STUDENT_ID` con tu legajo real
> antes de correr el `verify`.

> Los tests validan el CRUD **solo a través del `api-gateway`** (puerto 8080) y, además,
> declaran una **cola temporal bindeada a `cliente.exchange`** para comprobar que cada
> operación de escritura publicó el mensaje con el exchange, la routing key y el JSON esperados.

---

## Contexto del ecosistema (para resolver con cualquier IA)

Si vas a dirigir a un agente de IA, dale esta información además del enunciado. Son los
valores reales del proyecto que el `cliente-service` debe respetar para integrarse.

### Seguridad JWT

- Algoritmo **HS384**, mismo `jwt.secret` que `auth-service` y `api-gateway`:

```
jwt:
  secret: MiClaveSecretaParaJWT_MuyLarga_Y_Segura_123456789
```

- Patrón de referencia: `inventory-service` (resource server OAuth2 con `NimbusJwtDecoder` / `MacAlgorithm.HS384`).
- Rutas públicas: `/actuator/**`, `/h2-console/**`. El resto exige `Authorization: Bearer <token>`.
- Credenciales de prueba precargadas en `auth-service`: usuario `admin`, contraseña `admin123`.
- El login se hace contra el gateway: `POST http://localhost:8080/auth/login`.

### Ruta a configurar en el `api-gateway`

A diferencia de otros servicios, esta ruta **hay que agregarla** (no viene puesta):

```yaml
- id: cliente-service
  uri: lb://cliente-service
  predicates:
    - Path=/api/clientes/**
  filters:
    - AuthorizationHeader
```

### Configuración de RabbitMQ (patrón de `inventory-service`, perfil `rabbitmq`)

- Host `localhost`, puerto `5672`, usuario/contraseña `guest`/`guest`.
- Exchange `cliente.exchange` declarado como **topic** (`TopicExchange`).
- Conversor `Jackson2JsonMessageConverter` para serializar el evento a JSON.

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
```

### Registro en Eureka

```yaml
eureka:
  instance:
    prefer-ip-address: true
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

### Sugerencia de prompt para el agente de IA

> *"Implementá el módulo `cliente-service` en Spring Boot 3 / Java 21 para este proyecto Maven.
> Debe registrarse en Eureka (puerto 8085), exponer `/api/clientes` protegido con JWT HS384
> (mismo secret y patrón que `inventory-service`), persistir un `Cliente {id, nombre, telefono}`
> en H2, y publicar en el exchange topic RabbitMQ `cliente.exchange` con routing keys
> `cliente.created/updated/deleted` y un cuerpo JSON con `eventType`, `clienteId`, `nombre`,
> `telefono`, `occurredAt`. Agregá también la ruta `/api/clientes/**` en el api-gateway.
> La spec completa está en `parcial2_tema2_enunciado.md`."*

> **Tip:** cuando el agente genere código, leélo. Si algo no compila o el test falla,
> explicale el error. Ese intercambio es parte de lo que el docente observa.

---

*Fin del enunciado — Suerte.*
