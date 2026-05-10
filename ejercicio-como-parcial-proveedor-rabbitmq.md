# Ejercicio como parcial — Microservicio `proveedor-service` con RabbitMQ

## Objetivo

Implementar el microservicio **`proveedor-service`** en el ecosistema existente (**Spring Boot 3**, **Java 21**), exponiendo un **CRUD de Proveedor** bajo el API Gateway con **JWT** emitido vía `auth-service`. Además, el servicio debe **publicar eventos en RabbitMQ** cuando ocurran operaciones de escritura, según el contrato indicado más abajo.

Los estudiantes deben lograr que las **pruebas automáticas oficiales** del repositorio pasen en su entorno local. Esas pruebas validan el flujo **solo a través del `api-gateway`** (HTTP) y, además, **comprueban que el mensaje llegó al broker** con el exchange, routing key y cuerpo esperados.

**Sin Docker para microservicios**: Eureka, Config (si aplica), Auth, Proveedor, Gateway y RabbitMQ se ejecutan en la máquina local (IDE, `java -jar` o `mvn spring-boot:run`, y broker RabbitMQ instalado o accesible en `localhost`). *Opcionalmente* el docente puede permitir Docker; si no se indica lo contrario, se asume broker accesible en los puertos por defecto.

---

## Servicios y procesos que deben estar en ejecución

Antes de `mvn … verify` sobre el módulo de tests de integración:

| Componente | Puerto por defecto | Rol |
|------------|---------------------|-----|
| `eureka-server` | 8761 | Registro de servicios |
| `config-server` | 8888 | Configuración centralizada (recomendado alinear con el proyecto) |
| `auth-service` | 8083 | Emisión de JWT (`POST /auth/login` vía gateway según ruta expuesta) |
| `api-gateway` | 8080 | Único punto de entrada HTTP de los tests |
| **`proveedor-service`** | **8086** | CRUD + publicación AMQP |
| **RabbitMQ** (broker) | **5672** (AMQP); **15672** (UI management, si está habilitada) | Mensajería |

**Orden sugerido de arranque**: Eureka → Config (si aplica) → **RabbitMQ** → Auth → **Proveedor** → Gateway. El gateway necesita Eureka para resolver `lb://proveedor-service`. El `proveedor-service` debe poder conectarse al broker al iniciar (o fallar de forma clara si RabbitMQ no está disponible).

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

## RabbitMQ — Contrato obligatorio (solo publicador)

El `proveedor-service` debe **publicar mensajes** (no se exige implementar un microservicio consumidor en la entrega del alumno).

### Conexión

- Host por defecto asumido por los tests: **`localhost`**
- Puerto AMQP por defecto: **`5672`**
- Usuario / contraseña por defecto (salvo que el enunciado del curso o variables de entorno indiquen otro): típicamente `guest` / `guest` en instalación local.

Los tests oficiales pueden sobreescribir host, puerto, usuario y contraseña con variables de entorno (ver sección «Ejecución de tests»).

### Topología y nombres (cumplimiento estricto)

| Elemento | Valor obligatorio |
|----------|-------------------|
| Exchange | `proveedor.exchange` |
| Tipo de exchange | **topic** |
| Content-Type del mensaje | `application/json` (UTF-8) |

**Bindings**: el microservicio debe asegurar que el exchange exista y esté declarado de forma compatible con el consumo de los tests (topic). Las **routing keys** deben ser exactamente:

| Operación HTTP | Routing key |
|----------------|-------------|
| Crear proveedor (`POST`) | `proveedor.created` |
| Actualizar proveedor (`PUT`) | `proveedor.updated` |
| Eliminar proveedor (`DELETE`) | `proveedor.deleted` |

### Cuerpo del mensaje (JSON)

En **cada** publicación, el cuerpo debe ser un objeto JSON con al menos:

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

*(Opcional pedagógico)*: propagar estos valores al payload AMQP como metadatos; **no** es obligatorio salvo que el módulo de tests lo exija explícitamente en el código entregado.

---

## Ejecución de los tests oficiales

Los tests de integración están en el módulo **`exam-proveedor-rabbit-integration-tests`** (carpeta homónima en la raíz del repositorio; ver su `README.md`). El docente puede renombrar el módulo si lo documenta en el curso. Se ejecutan en la fase **`verify`** (plugin **Failsafe**), no en `test`, porque requieren microservicios y RabbitMQ levantados.

Desde la raíz del repositorio:

```bash
mvn -pl exam-proveedor-rabbit-integration-tests verify
```

**Variables de entorno** (ejemplos; el docente las fija en el `README` del módulo de examen):

| Variable | Significado | Por defecto típico |
|----------|-------------|-------------------|
| `GATEWAY_BASE_URL` | URL base del gateway | `http://localhost:8080` |
| `EXAM_STUDENT_ID` | Legajo / ID alumno | `NO-ASIGNADO` |
| `RABBITMQ_HOST` | Host del broker | `localhost` |
| `RABBITMQ_PORT` | Puerto AMQP | `5672` |
| `RABBITMQ_USERNAME` | Usuario AMQP | `guest` |
| `RABBITMQ_PASSWORD` | Contraseña AMQP | `guest` |

**Qué verifican los tests (resumen)**: inicio de sesión vía gateway, operaciones CRUD vía gateway con JWT, y **recepción de al menos un mensaje** por operación que deba publicar, con exchange, routing key y JSON según este enunciado (con timeouts razonables).

---

## Entrega

Según indique el docente: repositorio, ZIP o rama con:

- Módulo **`proveedor-service`** completo.
- Cambios necesarios en **`api-gateway`** (ruta `/api/proveedores/**`) y en configuración compartida **sin romper** otros servicios.
- Cualquier ajuste en **`pom.xml`** raíz para incluir el módulo de tests de integración, si aplica.

En **este** repositorio el módulo `exam-proveedor-rabbit-integration-tests` ya está referenciado en el `pom` raíz. Un `mvn install` / `mvn verify` sobre todo el reactor **fallará** en la fase de integración del examen si no hay microservicios ni RabbitMQ; para compilar sin ejecutar esos IT: `mvn … -DskipITs`.

---

## Criterio de aprobación sugerido

- **`mvn -pl exam-proveedor-rabbit-integration-tests verify`** en **verde** en la máquina del corrector (o en el entorno oficial de laboratorio), con la pila de servicios y RabbitMQ según este documento.
