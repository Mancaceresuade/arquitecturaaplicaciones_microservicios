# Examen práctico — Microservicio `cliente-service`

## Objetivo

Implementar el microservicio **`cliente-service`** en el ecosistema existente (Spring Boot 3, Java 21), exponiendo un CRUD de **Cliente** y haciendo que las pruebas automáticas oficiales pasen **solo a través del `api-gateway`**, con **JWT** obtenido del `auth-service`.

**Sin Docker**: todo se ejecuta en la máquina local (IDE o `java -jar` / `mvn spring-boot:run`).

---

## Servicios que deben estar en ejecución

Antes de correr los tests de integración, deben levantarse al menos:

| Servicio        | Puerto por defecto | Rol |
|-----------------|-------------------|-----|
| `eureka-server` | 8761              | Registro de servicios |
| `config-server` | 8888              | Configuración centralizada (opcional si `cliente-service` no la usa; recomendado alinear con el resto del proyecto) |
| `auth-service`  | 8083              | Emisión de JWT (`POST /auth/login`) |
| `api-gateway`   | 8080              | Único punto de entrada HTTP para los tests |
| `cliente-service` | **8086** (definido abajo) | CRUD de clientes |

**Orden sugerido**: Eureka → Config (si aplica) → Auth → **Cliente** → Gateway (el gateway necesita Eureka para resolver `lb://cliente-service`).

---

## Registro en Eureka y nombre del servicio

- `spring.application.name` **debe ser** exactamente: `cliente-service`
- El servicio debe registrarse en Eureka para que el gateway use `lb://cliente-service`.

---

## Ruta en el API Gateway (obligatorio)

En el repositorio base ya se incluye la ruta hacia `cliente-service`. Si trabajan en una copia antigua, deben asegurarse de que el gateway enrute:

- **Predicado**: `Path=/api/clientes/**`
- **URI**: `lb://cliente-service`
- **Filtro**: el mismo mecanismo que el resto de rutas protegidas (propagación de `Authorization`), como en `inventory-service` o `graph-service`.

Los tests **no** llaman al puerto 8086 directamente; solo a `http://localhost:8080` (gateway).

---

## Seguridad

- Todas las operaciones del CRUD bajo `/api/clientes/**` deben exigir **JWT válido** (mismo `jwt.secret` que `auth-service` y `api-gateway`: recurso OAuth2 JWT, algoritmo **HS384**, igual que `inventory-service`).
- Rutas públicas típicas: `/actuator/**`, `/h2-console/**` (si habilitan consola H2), según configuren.

**Credenciales de prueba** (usuarios precargados en `auth-service`):

- Usuario: `admin` — Contraseña: `admin123`

Los tests obtienen el token con `POST /auth/login` contra el **gateway** (puerto 8080).

---

## Persistencia

- Base **H2 en memoria** (`jdbc:h2:mem:...`).
- El **`id`** es **entero largo (`Long`)**, **generado por la base** (identidad).  
  **No** debe enviarse en el cuerpo del **POST** de creación; si se envía, debe ignorarse o no estar presente.

---

## Contrato HTTP exacto (cumplimiento estricto)

**Base path en el microservicio**: `/api/clientes`  
(Todo ello accesible vía gateway como `http://localhost:8080/api/clientes/...`.)

### Modelo JSON — Cliente

| Campo      | Tipo   | Reglas |
|------------|--------|--------|
| `id`       | number | Solo lectura; generado por H2 en INSERT |
| `nombre`   | string | Obligatorio; no vacío; máximo 200 caracteres |
| `telefono` | string | Obligatorio; no vacío; máximo 30 caracteres |

Nombres de propiedad en JSON: **`id`**, **`nombre`**, **`telefono`** (minúsculas, como en la tabla).

### Crear — `POST /api/clientes`

- **Headers**: `Authorization: Bearer <token>`, `Content-Type: application/json`
- **Body** (ejemplo):

```json
{
  "nombre": "Ana Gómez",
  "telefono": "+54 11 1234-5678"
}
```

- **Respuesta exitosa**: **201 Created**
- **Body**: objeto Cliente completo **incluyendo** `id` generado
- **Errores de validación**: **400 Bad Request** (cuerpo opcional; los tests solo verifican el código)

### Listar — `GET /api/clientes`

- **Headers**: `Authorization: Bearer <token>`
- **Respuesta**: **200 OK** con un **array JSON** de clientes (puede ser `[]` si no hay datos)

### Obtener uno — `GET /api/clientes/{id}`

- **`{id}`**: entero largo
- **200 OK** + Cliente si existe  
- **404 Not Found** si no existe

### Actualizar — `PUT /api/clientes/{id}`

- **Body** (misma forma que crear, sin requerir `id` en el body):

```json
{
  "nombre": "Ana Gómez Ruiz",
  "telefono": "+54 11 9999-0000"
}
```

- **200 OK** + Cliente actualizado si existe  
- **404** si no existe  
- **400** si datos inválidos

### Eliminar — `DELETE /api/clientes/{id}`

- **204 No Content** si se borró correctamente  
- **404** si no existía

---

## Headers de trazabilidad (examen / anti-copia)

Los tests automáticos envían en **cada** petición autenticada al gateway (y deben poder reenviarse al servicio si el gateway propaga headers por defecto; en Spring Cloud Gateway suele propagarse el conjunto de headers de la petición):

| Header              | Origen |
|---------------------|--------|
| `X-Exam-Student-Id` | Variable de entorno **`EXAM_STUDENT_ID`** (legajo o ID que asigne el docente). Si no está definida, el test usa un valor por defecto marcador. |
| `X-Exam-Machine-Id` | **Nombre del host** de la máquina donde se ejecutan los tests (`InetAddress.getLocalHost().getHostName()` en el runner). |

**Qué sí y qué no hace esto**

- **Sí** ayuda a correlacionar ejecuciones (por ejemplo logs o capturas) con un legajo y una máquina.
- **No** impide que dos personas copien el **código** del microservicio: para eso hacen falta otras medidas (entrega individual, preguntas orales, revisión de commits, herramientas anti-plagio, variantes del enunciado, etc.).

Se recomienda que cada alumno configure **`EXAM_STUDENT_ID`** antes de correr los tests (por ejemplo en variables de entorno del IDE o del sistema).

---

## Cómo ejecutar los tests oficiales

Los tests de integración están en el módulo `exam-cliente-integration-tests` y se ejecutan en la fase **`verify`** (plugin **Failsafe**), no en `test`, porque requieren los microservicios levantados.

Desde la raíz del repositorio:

```bash
mvn -pl exam-cliente-integration-tests verify
```

(`mvn test` en ese módulo no ejecuta los `*IT.java`.)

Variables opcionales:

| Variable           | Significado | Por defecto |
|--------------------|-------------|-------------|
| `GATEWAY_BASE_URL` | URL base del gateway | `http://localhost:8080` |
| `EXAM_STUDENT_ID`  | Legajo / ID alumno   | `NO-ASIGNADO` |

---

## Entrega

Según indique el docente: repositorio, ZIP o branch con el módulo `cliente-service` completo y cualquier cambio necesario en configuración compartida (sin romper otros servicios).

---

## Criterio de aprobación sugerido

- Los tests del módulo `exam-cliente-integration-tests` pasan en la máquina del docente con el gateway y los servicios comunes levantados según esta consigna.
