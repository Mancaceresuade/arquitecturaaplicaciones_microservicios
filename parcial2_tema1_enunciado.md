# Parcial 2 — Tema 1
### Arquitectura de Aplicaciones · UADE · Clases 8–14

**Parte práctica: implementar `proveedor-service` con Apache Kafka**

> El agente de IA (Cursor, Claude Code, Copilot, etc.) **está permitido y es parte del examen**.
> Lo que se evalúa es si podés dirigirlo correctamente y entender lo que produce.

---

## Parte 1 — Práctica: implementar `proveedor-service`

### Contexto

El ecosistema base ya tiene corriendo:

| Servicio | Puerto |
|----------|--------|
| `eureka-server` | 8761 |
| `config-server` | 8888 |
| `auth-service` | 8083 |
| `api-gateway` | 8080 |
| Kafka Broker | 9092 |
| Zookeeper / KRaft | 2181 |

Tu tarea es implementar el módulo **`proveedor-service`** dentro del proyecto que ya tenés clonado.

### Qué tenés que construir

Un microservicio Spring Boot 3 / Java 21 que:

1. Se registra en Eureka con `spring.application.name=proveedor-service` en el puerto **8086**.
2. Expone bajo `/api/proveedores/**` las rutas protegidas con JWT (HS384, mismo secret del ecosistema).
3. Persiste en **H2 en memoria**. El modelo `Proveedor` tiene:
   - `id` → Long, generado por H2 (solo lectura; si llega en el body de un POST se ignora)
   - `nombre` → String, obligatorio, no vacío, máx. 200 caracteres
   - `telefono` → String, obligatorio, no vacío, máx. 30 caracteres

4. Implementa el flujo Kafka **como publicador** sobre el topic `proveedor.events`:
   - **POST /api/proveedores** → persiste → publica con `key=proveedor.created`
   - **PUT /api/proveedores/{id}** → actualiza → publica con `key=proveedor.updated`
   - **DELETE /api/proveedores/{id}** → borra → publica con `key=proveedor.deleted`

   No se exige implementar un consumidor: alcanza con publicar después de confirmar la persistencia.

5. El `api-gateway` ya tiene configurada la ruta `Path=/api/proveedores/**` → `lb://proveedor-service`.

### Contrato HTTP

```
POST   /api/proveedores        → 201 Created + {id, nombre, telefono}
GET    /api/proveedores        → 200 + array (puede ser [])
GET    /api/proveedores/{id}   → 200 o 404
PUT    /api/proveedores/{id}   → 200 o 404
DELETE /api/proveedores/{id}   → 204 o 404
```
Validación fallida → **400**. Sin JWT → **401**.

### Contrato Kafka (cumplimiento estricto)

| Elemento | Valor obligatorio |
|----------|-------------------|
| Topic | `proveedor.events` |
| Key (POST) | `proveedor.created` |
| Key (PUT) | `proveedor.updated` |
| Key (DELETE) | `proveedor.deleted` |
| Value | JSON UTF-8 |

El **value** de cada mensaje es un objeto JSON con al menos:

| Campo | Tipo | Reglas |
|-------|------|--------|
| `eventType` | string | `PROVEEDOR_CREATED`, `PROVEEDOR_UPDATED` o `PROVEEDOR_DELETED` según la operación |
| `proveedorId` | number | `id` del proveedor afectado (Long) |
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
mvn -pl exam-proveedor-integration-tests verify
```

**Aprobás esta parte si todos los tests quedan en verde.**
El reporte queda en `exam-proveedor-integration-tests/target/exam-report/resultado-examen.json`.

> El reporte incluye tu **legajo** (`examStudentId`, tomado de `EXAM_STUDENT_ID`) y un
> **indicador de la máquina** (`examMachineId` = hostname, y `machineFingerprint` = hash
> corto de la PC). Sirven solo como referencia de quién y dónde se corrió el test; no es
> un mecanismo de seguridad. Asegurate de setear `EXAM_STUDENT_ID` con tu legajo real
> antes de correr el `verify`.

> Los tests validan el CRUD **solo a través del `api-gateway`** (puerto 8080) y, además,
> abren un **consumidor Kafka real** sobre `proveedor.events` para comprobar que cada
> operación de escritura publicó el mensaje con el topic, la key y el JSON esperados.

---

## Contexto del ecosistema (para resolver con cualquier IA)

Si vas a dirigir a un agente de IA, dale esta información además del enunciado. Son los
valores reales del proyecto que el `proveedor-service` debe respetar para integrarse.

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

### Ruta ya configurada en el `api-gateway`

```yaml
- id: proveedor-service
  uri: lb://proveedor-service
  predicates:
    - Path=/api/proveedores/**
  filters:
    - AuthorizationHeader
```

### Configuración de Kafka (patrón de `inventory-service`)

- Bootstrap servers: `localhost:9092` (perfil `kafka`).
- Productor: `StringSerializer` para la key, `JsonSerializer` para el value.

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
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

> *"Implementá el módulo `proveedor-service` en Spring Boot 3 / Java 21 para este proyecto Maven.
> Debe registrarse en Eureka (puerto 8086), exponer `/api/proveedores` protegido con JWT HS384
> (mismo secret y patrón que `inventory-service`), persistir un `Proveedor {id, nombre, telefono}`
> en H2, y publicar en el topic Kafka `proveedor.events` con keys `proveedor.created/updated/deleted`
> y un value JSON con `eventType`, `proveedorId`, `nombre`, `telefono`, `occurredAt`.
> La spec completa está en `parcial2_tema1_enunciado.md`."*

> **Tip:** cuando el agente genere código, leélo. Si algo no compila o el test falla,
> explicale el error. Ese intercambio es parte de lo que el docente observa.

---

*Fin del enunciado — Suerte.*
