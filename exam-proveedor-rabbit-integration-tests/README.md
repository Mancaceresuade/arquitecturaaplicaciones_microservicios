# exam-proveedor-rabbit-integration-tests

Pruebas oficiales del ejercicio descrito en `ejercicio-como-parcial-proveedor-rabbitmq.md`.

## Requisitos previos

Antes de ejecutar `verify`, deben estar en marcha (entre otros) **Eureka**, **Auth**, **API Gateway**, **RabbitMQ** y el microservicio **`proveedor-service`** registrado en Eureka, con la ruta del gateway `/api/proveedores/**` configurada. El exchange `proveedor.exchange` (topic) debe existir cuando arranque el servicio del alumno.

## Comando

Desde la raíz del repositorio:

```bash
mvn -pl exam-proveedor-rabbit-integration-tests verify
```

Para compilar el módulo **sin** ejecutar los tests de integración (por ejemplo en CI sin broker):

```bash
mvn -pl exam-proveedor-rabbit-integration-tests verify -DskipITs
```

## Variables de entorno

| Variable | Por defecto |
|----------|----------------|
| `GATEWAY_BASE_URL` | `http://127.0.0.1:8080` (si ponés `localhost`, el test lo reescribe a `127.0.0.1` para evitar `ConnectException` por IPv6 en Windows) |
| `EXAM_STUDENT_ID` | `NO-ASIGNADO` |
| `RABBITMQ_HOST` | `127.0.0.1` en el código de test si no está definido; `localhost` se normaliza a `127.0.0.1` |
| `RABBITMQ_PORT` | `5672` |
| `RABBITMQ_USERNAME` | `guest` |
| `RABBITMQ_PASSWORD` | `guest` |

## Nota

Si el módulo `proveedor-service` aún no está implementado en el repositorio, estos tests **fallarán** hasta que exista el servicio, el gateway enrute correctamente y se publiquen los eventos según el contrato del ejercicio.

### `java.net.ConnectException` en el login

Eso significa que **no se pudo abrir TCP al API Gateway** (por defecto `http://127.0.0.1:8080`). No es un error de `proveedor-service`. Comprobá que **api-gateway** esté en marcha **después** de Eureka y que responda `GET /actuator/health` en el puerto configurado. Si usás otra URL (Codespaces, Docker, otro host), exportá `GATEWAY_BASE_URL` (sin barra final). Los tests de Failsafe arrancan con `-Djava.net.preferIPv4Stack=true` para reducir problemas de `localhost` → `::1` vs gateway solo en IPv4.

### Maven en WSL2 y servicios en Windows (o Docker Desktop)

En WSL, `127.0.0.1` es el loopback **de Linux**, no el host Windows donde corre el IDE/gateway. El test prueba en orden: `GATEWAY_BASE_URL` (si existe), `http://127.0.0.1:8080`, `http://localhost:8080` y, **solo si detecta WSL**, `http://<IP-del-nameserver-en-/etc/resolv.conf>:8080` (suele ser el Windows host). Si aun así falla, en la misma terminal WSL:

`export GATEWAY_BASE_URL=http://$(grep nameserver /etc/resolv.conf | head -1 | awk '{print $2}'):8080`

Sin `RABBITMQ_HOST`, en WSL el cliente AMQP usa esa misma IP del host para llegar a Rabbit publicado en Windows.

### Sigue sin conectar en Windows “puro”

Comprobá que algo escuche en 8080: `Test-NetConnection 127.0.0.1 -Port 8080` (PowerShell). Si el gateway usa otro puerto, definí `GATEWAY_BASE_URL` con ese puerto.

Antes de `verify`, el test espera hasta **90 s** a que el gateway responda **cualquier código HTTP 2xx–5xx** en `GET /actuator/health` (conexión OK). No exige 200: con Spring Cloud Gateway el health agregado suele ser **503** mientras Eureka aún no marca el contexto como UP; el gateway igual puede enrutar `/auth/login`.
