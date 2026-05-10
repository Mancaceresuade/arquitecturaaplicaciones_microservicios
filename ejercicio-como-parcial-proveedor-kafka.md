# Ejercicio como parcial — Microservicio `proveedor-service` con Apache Kafka

## Objetivo

Implementar el microservicio **`proveedor-service`** en el ecosistema existente (**Spring Boot 3**, **Java 21**), exponiendo un **CRUD de Proveedor** bajo el API Gateway con **JWT** emitido vía `auth-service`. Además, el servicio debe **publicar eventos en Kafka** cuando ocurran operaciones de escritura, según el contrato indicado más abajo.

Los estudiantes deben lograr que las **pruebas automáticas oficiales** del repositorio pasen en su entorno local. Esas pruebas validan el flujo **solo a través del `api-gateway`** (HTTP) y, además, **comprueban que el registro llegó al broker** con el topic, clave (`key`), cuerpo y cabeceras esperados (cuando apliquen).

**Sin Docker para microservicios**: Eureka, Config (si aplica), Auth, Proveedor, Gateway y Kafka Broker se ejecutan en la máquina local (IDE, `java -jar` o `mvn spring-boot:run`, y broker Kafka accesible en `localhost`). *Opcionalmente* el docente puede permitir Docker; si no se indica lo contrario, se asume broker accesible en los puertos por defecto.

---

## Servicios y procesos que deben estar en ejecución

Antes de `mvn … verify` sobre el módulo de tests de integración:

| Componente | Puerto por defecto | Rol |
|------------|---------------------|-----|
| `eureka-server` | 8761 | Registro de servicios |
| `config-server` | 8888 | Configuración centralizada (recomendado alinear con el proyecto) |
| `auth-service` | 8083 | Emisión de JWT (`POST /auth/login` vía gateway según ruta expuesta) |
| `api-gateway` | 8080 | Único punto de entrada HTTP de los tests |
| **`proveedor-service`** | **8086** | CRUD + publicación Kafka |
| **Kafka Broker** | **9092** (listeners por defecto; `$KAFKA_*` pueden variarlo) | Mensajería |

**Orden sugerido de arranque**: Eureka → Config (si aplica) → **Kafka / ZooKeeper o KRaft según instalación** → Auth → **Proveedor** → Gateway. El gateway necesita Eureka para resolver `lb://proveedor-service`. El `proveedor-service` debe poder conectarse al cluster al iniciar (o fallar de forma clara si Kafka no está disponible).

El topic **`proveedor.events`** debe estar disponible cuando se ejecuten los tests (por creación previa administrativa o porque el broker permite `auto.create.topics.enable=true`; ver `README.md` del módulo de examen).

---

## Registro en Eureka

- `spring.application.name` **exactamente**: `proveedor-service`
- El servicio debe registrarse en Eureka para que el gateway use `lb://proveedor-service`.

---

## Ruta en el API Gateway (obligatorio)

Configurar en el gateway:

- **Predicado**: `Path=/api/proveedores/**`
- **URI**: `lb://proveedor-service`
- **Filtros**: el mismo mecanismo que el resto de rutas protegidas (propagación de `Authorization`), en línea con `inventory-service` u otro servicio de referencia del proyecto.

Los tests **no** invocan el puerto **8086** directamente; solo la URL base del gateway (por defecto `http://localhost:8080`).

---

## Seguridad (HTTP)

- Todas las operaciones del CRUD bajo `/api/proveedores/**` exigen **JWT válido** (mismo `jwt.secret` / recurso OAuth2 JWT que `auth-service` y `api-gateway`, algoritmo **HS384**, igual que el patrón de `inventory-service`).
- Rutas públicas habituales: `/actuator/**`, `/h2-console/**` (si las habilitan), según criterio del proyecto.

**Credenciales de prueba** (usuarios precargados en `auth-service`):

- Usuario: `admin` — Contraseña: `admin123`

Los tests obtienen el token con **`POST /auth/login`** contra el **gateway** (puerto 8080), según la ruta pública configurada en el proyecto.

---

## Apache Kafka — Contrato obligatorio (solo publicador)

El `proveedor-service` debe **publicar registros** (no se exige implementar un microservicio consumidor en la entrega del alumno).

### Conexión

- **Bootstrap servers** por defecto asumido por los tests: **`127.0.0.1:9092`** (cadena tipo `host:puerto`; en entornos multi-broker puede listarse más de uno separado por comas).

Los tests oficiales pueden sobreescribir los bootstrap servers con variable de entorno (ver sección «Ejecución de tests»).

### Topic y claves (cumplimiento estricto)

| Elemento | Valor obligatorio |
|----------|-------------------|
| Topic | `proveedor.events` |
| **Key** del registro (`ProducerRecord` / API equivalente) | Depende de la operación (ver tabla siguiente) |
| **Value** | JSON **UTF-8** con la forma descrita abajo |

**Keys** obligatorias (análogas a las routing keys AMQP del ejercicio con RabbitMQ):

| Operación HTTP | Key del mensaje |
|----------------|------------------|
| Crear proveedor (`POST`) | `proveedor.created` |
| Actualizar proveedor (`PUT`) | `proveedor.updated` |
| Eliminar proveedor (`DELETE`) | `proveedor.deleted` |

### Cabeceras (recomendado / validación en tests)

Se recomienda enviar una cabecera de registro:

| Cabecera | Valor |
|----------|--------|
| `content-type` | `application/json` |

Si el productor no envía esta cabecera, las pruebas oficiales **no** deben fallar solo por su ausencia; si está presente, debe ser coherente con JSON.

### Cuerpo del mensaje (value, JSON)

En **cada** publicación, el value debe ser un objeto JSON con al menos:

| Campo | Tipo | Reglas |
|-------|------|--------|
| `eventType` | string | Uno de: `PROVEEDOR_CREATED`, `PROVEEDOR_UPDATED`, `PROVEEDOR_DELETED` según corresponda |
| `proveedorId` | number | `id` del proveedor afectado (Long) |
| `nombre` | string | Valor actual del nombre tras la operación (en `DELETED`, se recomienda enviar el último valor conocido antes del borrado) |
| `telefono` | string | Mismo criterio que `nombre` |
| `occurredAt` | string | Instantánea en **ISO-8601** (por ejemplo con offset `Z`) |

Los tests oficiales validarán presencia y tipos de estos campos y la coherencia básica con la respuesta HTTP previa.

**Orden sugerido**: publicar **después** de confirmar persistencia exitosa (transacción confirmada o equivalente), para evitar eventos huérfanos si falla la base.

---

## Persistencia

- Base **H2 en memoria** (`jdbc:h2:mem:...`).
- El **`id`** es **`Long`**, **generado por la base** (identidad). En **POST** no debe enviarse en el cuerpo; si se envía, debe **ignorarse**.

---

## Contrato HTTP del CRUD (cumplimiento estricto)

**Base path en el microservicio**: `/api/proveedores`  
(accesible como `http://localhost:8080/api/proveedores/...` a través del gateway.)

### Modelo JSON — Proveedor

| Campo | Tipo | Reglas |
|-------|------|--------|
| `id` | number | Solo lectura; generado en INSERT |
| `nombre` | string | Obligatorio; no vacío; máximo 200 caracteres |
| `telefono` | string | Obligatorio; no vacío; máximo 30 caracteres |

Nombres en JSON: **`id`**, **`nombre`**, **`telefono`** (minúsculas).

### Endpoints

- **`POST /api/proveedores`** — Body: `nombre`, `telefono`. **201 Created** + cuerpo con `id` generado. Validación: **400**.
- **`GET /api/proveedores`** — **200** + array JSON (puede ser `[]`).
- **`GET /api/proveedores/{id}`** — **200** si existe; **404** si no.
- **`PUT /api/proveedores/{id}`** — Body como crear (sin exigir `id`). **200** si existe; **404** si no; **400** si inválido.
- **`DELETE /api/proveedores/{id}`** — **204** si se borró; **404** si no existía.

Headers en operaciones autenticadas: `Authorization: Bearer <token>`, y `Content-Type: application/json` donde corresponda.

---

## Headers de trazabilidad

En **cada** petición autenticada al gateway, los tests envían:

| Header | Origen |
|--------|--------|
| `X-Exam-Student-Id` | Variable de entorno **`EXAM_STUDENT_ID`** (legajo). Si no está definida, valor por defecto marcador. |
| `X-Exam-Machine-Id` | Nombre de host de la máquina que ejecuta los tests. |

Se recomienda configurar **`EXAM_STUDENT_ID`** antes de correr los tests.

*(Opcional pedagógico)*: propagar estos valores al payload Kafka como metadatos adicionales; **no** es obligatorio salvo que el módulo de tests lo exija explícitamente en el código entregado.

---

## Ejecución de los tests oficiales

Los tests de integración están en el módulo **`exam-proveedor-kafka-integration-tests`** (carpeta homónima en la raíz del repositorio; ver su `README.md`). El docente puede renombrar el módulo si lo documenta en el curso. Se ejecutan en la fase **`verify`** (plugin **Failsafe**), no en `test`, porque requieren microservicios y Kafka levantados.

Desde la raíz del repositorio:

```bash
mvn -pl exam-proveedor-kafka-integration-tests verify
```

**Variables de entorno** (ejemplos; el docente las fija en el `README` del módulo de examen):

| Variable | Significado | Por defecto típico |
|----------|-------------|-------------------|
| `GATEWAY_BASE_URL` | URL base del gateway | `http://localhost:8080` |
| `EXAM_STUDENT_ID` | Legajo / ID alumno | `NO-ASIGNADO` |
| `KAFKA_BOOTSTRAP_SERVERS` | Brokers Kafka (`host:puerto` o lista separada por comas) | `127.0.0.1:9092` |

**Qué verifican los tests (resumen)**: inicio de sesión vía gateway, operaciones CRUD vía gateway con JWT, y **recepción de al menos un registro** por operación que deba publicar, con topic, key y JSON según este enunciado (con timeouts razonables).

---

## Entrega

Según indique el docente: repositorio, ZIP o rama con:

- Módulo **`proveedor-service`** completo (incluyendo productor Kafka según este contrato).
- Cambios necesarios en **`api-gateway`** (ruta `/api/proveedores/**`) y en configuración compartida **sin romper** otros servicios.
- Cualquier ajuste en **`pom.xml`** raíz para incluir el módulo de tests de integración, si aplica.

En **este** repositorio el módulo `exam-proveedor-kafka-integration-tests` ya está referenciado en el `pom` raíz. Un `mvn install` / `mvn verify` sobre todo el reactor **fallará** en la fase de integración del examen si no hay microservicios ni Kafka; para compilar sin ejecutar esos IT: `mvn … -DskipITs`.

---

## Criterio de aprobación sugerido

- **`mvn -pl exam-proveedor-kafka-integration-tests verify`** en **verde** en la máquina del corrector (o en el entorno oficial de laboratorio), con la pila de servicios y Kafka según este documento.
