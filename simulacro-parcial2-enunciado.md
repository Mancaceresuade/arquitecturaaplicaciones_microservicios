# Simulacro — Segundo Parcial
### Arquitectura de Aplicaciones · UADE · Clases 8–14

**Tiempo total: 30 minutos · Grupos de hasta 8 personas**

---

## Resumen del examen

| Parte | Modalidad | Tiempo sugerido | Peso |
|-------|-----------|-----------------|------|
| 1 — Práctica con IA | Implementar `pedido-service` hasta que los tests pasen | 17 min | 50 % |
| 2 — Formulario | Respuestas escritas (en papel o digital) | 8 min | 30 % |
| 3 — Live coding | Modificación simple en vivo ante el docente | 5 min | 20 % |

> El agente de IA (Cursor, Claude Code, Copilot, etc.) **está permitido y es parte del examen**.
> Lo que se evalúa es si podés dirigirlo correctamente y entender lo que produce.
Condición de aprobación: las tres partes aprobadas

---

## Parte 1 — Práctica: implementar `pedido-service` *(17 min)*

### Contexto

El ecosistema base ya tiene corriendo:

| Servicio | Puerto |
|----------|--------|
| `eureka-server` | 8761 |
| `config-server` | 8888 |
| `auth-service` | 8083 |
| `api-gateway` | 8080 |
| Kafka Broker | 9092 |
| Zookeeper | 2181 |

Tu tarea es implementar el módulo **`pedido-service`** dentro del proyecto que ya tenés clonado.

### Qué tenés que construir

Un microservicio Spring Boot 3 / Java 21 que:

1. Se registra en Eureka con `spring.application.name=pedido-service` en el puerto **8087**.
2. Expone bajo `/api/pedidos/**` las rutas protegidas con JWT (HS384, mismo secret del ecosistema).
3. Persiste en **H2 en memoria**. El modelo `Pedido` tiene:
   - `id` → Long, generado por H2
   - `descripcion` → String, obligatorio, no vacío, máx. 255 caracteres
   - `cantidad` → int, obligatorio, mínimo 1
   - `estado` → String, solo lectura: `PENDIENTE` o `CONFIRMADO`

4. Implementa el flujo Kafka completo:
   - **POST /api/pedidos** → persiste con estado `PENDIENTE` → publica en `pedidos-topic`
   - El mismo servicio **consume** `pedidos-topic` → actualiza el estado a `CONFIRMADO`

5. El `api-gateway` ya tiene configurada la ruta `Path=/api/pedidos/**` → `lb://pedido-service`.

### Contrato HTTP

```
POST /api/pedidos         → 201 Created + {id, descripcion, cantidad, estado:"PENDIENTE"}
GET  /api/pedidos         → 200 + array
GET  /api/pedidos/{id}    → 200 o 404
```
Validación fallida → **400**. Sin JWT → **401**.

### Cómo verificar

```bash
# Configurar tu legajo (reemplazá 12345 por el tuyo)
set EXAM_STUDENT_ID=12345           # Windows CMD
# export EXAM_STUDENT_ID=12345     # Git Bash / Linux

# Correr los tests de integración
mvn -pl exam-pedido-integration-tests verify
```

**Aprobás esta parte si los 8 tests quedan en verde.**
El reporte queda en `exam-pedido-integration-tests/target/exam-report/resultado-examen.json`.

> El reporte incluye tu **legajo** (`examStudentId`, tomado de `EXAM_STUDENT_ID`) y un
> **indicador de la máquina** (`examMachineId` = hostname, y `machineFingerprint` = hash
> corto de la PC). Sirven solo como referencia de quién y dónde se corrió el test; no es
> un mecanismo de seguridad. Asegurate de setear `EXAM_STUDENT_ID` con tu legajo real
> antes de correr el `verify`.

### Sugerencia de prompt para el agente de IA

> *"Implementá el módulo `pedido-service` en Spring Boot 3 / Java 21 para este proyecto Maven.
> Debe registrarse en Eureka (puerto 8087), exponer `/api/pedidos` protegido con JWT HS384,
> persistir en H2, y tener un productor + consumidor Kafka para el topic `pedidos-topic`
> que cambia el estado del pedido de PENDIENTE a CONFIRMADO. La spec completa está en `parcial2.md`."*

> **Tip:** cuando el agente genere código, leélo. Si algo no compila o el test falla,
> explicale el error. Ese intercambio es parte de lo que el docente observa.

---

## Parte 2 — Formulario *(8 min)*

Respondé con dos o tres oraciones. No es necesario escribir código.

---

**Pregunta 1 · Patrones de mensajería**

> El equipo de QA detecta que cada vez que `notification-service` está caído, los pedidos
> dejan de crearse correctamente. El cliente exige que el sistema siga funcionando aunque
> `notification-service` no esté disponible.
>
> ¿Qué patrón arquitectónico usarías para resolver esto y por qué?
> Mencioná una tecnología concreta del proyecto que ya lo implementa.

---

**Pregunta 2 · Observabilidad**

> Un usuario reporta que una operación `POST /api/pedidos` tardó 8 segundos pero no sabe
> en qué microservicio ocurrió el cuello de botella (gateway, auth-service o pedido-service).
>
> ¿Qué herramienta del stack de observabilidad usarías para diagnosticarlo?
> Explicá en dos pasos cómo encontrarías el span lento.

---

**Pregunta 3 · Cloud Native**

> Tu equipo decide deployar el proyecto a AWS ECS Fargate.
> El tech lead dice: *"Tenemos que eliminar Eureka"*.
>
> ¿Por qué tiene razón? ¿Con qué lo reemplazarías en AWS y cómo afecta
> la configuración del `api-gateway`?

---

**Pregunta 4 · Diseño (opcional — suma puntos)**

> Describí en dos oraciones cómo agregarías un **Dead Letter Topic** al flujo Kafka actual
> para que pedidos con `cantidad > 100` sean rechazados y no bloqueen la cola principal.

---

## Parte 3 — Live coding ante el docente *(5 min)*

El docente te asignará **uno** de los siguientes ejercicios con los servicios ya corriendo.
No se evalúa la perfección del resultado sino **cómo encarás el problema**:
¿Sabés en qué archivo tocar? ¿Describís bien lo que querés al agente de IA? ¿Verificás con curl?

### Ejercicio A — Endpoint de estado ⭐
Agregá en `pedido-service` un endpoint `GET /api/pedidos/health/custom`
que devuelva `{"service":"pedido-service","status":"UP"}`. Sin JWT.
Verificación: `curl http://localhost:8080/api/pedidos/health/custom`

### Ejercicio B — Header de respuesta ⭐
Modificá `GET /api/pedidos` para que la respuesta incluya el header
`X-Total-Count` con la cantidad de pedidos devueltos.
Verificación: `curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/pedidos`

### Ejercicio C — Log estructurado ⭐
Agregá un log `INFO` al controller que muestre `id` y `estado` en cada
`GET /api/pedidos/{id}`.
Verificación: ver consola del `pedido-service` al hacer el request.

### Ejercicio D — Propiedad externa ⭐⭐
Agregá `app.pedido.max-cantidad=500` en `config-server/config/pedido-service.yml`
y leéla con `@Value` en un `@Component`, imprimiéndola con `@PostConstruct`.
Verificación: ver consola del servicio al reiniciar.

### Ejercicio E — Validación nueva ⭐⭐
Modificá el `POST /api/pedidos` para rechazar con **400** si `cantidad > 1000`.
Los tests existentes usan cantidad=2, así que no deben romperse.
Verificación: `curl -X POST ... -d '{"descripcion":"x","cantidad":1001}'` → 400.

---

*Fin del enunciado — Suerte.*
