# exam-cliente-integration-tests

Pruebas oficiales del ejercicio descrito en `parcial2_tema2_enunciado.md`.

## Requisitos previos

Antes de ejecutar `verify`, deben estar en marcha:

| Componente        | Puerto |
|-------------------|--------|
| RabbitMQ Broker   | 5672   |
| `eureka-server`   | 8761   |
| `auth-service`    | 8083   |
| `api-gateway`     | 8080   |
| `cliente-service` | 8085   |

El gateway debe enrutar `Path=/api/clientes/**` → `lb://cliente-service`.

## Comando

Desde la raíz del repositorio:

```bash
mvn -pl exam-cliente-integration-tests verify
```

Para compilar sin ejecutar integración:

```bash
mvn -pl exam-cliente-integration-tests verify -DskipITs
```

## Variables de entorno

| Variable            | Por defecto             |
|---------------------|-------------------------|
| `GATEWAY_BASE_URL`  | `http://127.0.0.1:8080` |
| `EXAM_STUDENT_ID`   | `NO-ASIGNADO`           |
| `RABBITMQ_HOST`     | `localhost`             |
| `RABBITMQ_PORT`     | `5672`                  |
| `RABBITMQ_USERNAME` | `guest`                 |
| `RABBITMQ_PASSWORD` | `guest`                 |

Configurar legajo antes de correr:

```bash
export EXAM_STUDENT_ID=tu-legajo
```

## Casos cubiertos

| #  | Test                                | Verifica                                            |
|----|-------------------------------------|-----------------------------------------------------|
| 1  | `POST /api/clientes`                | 201 + id + body                                     |
| 2  | Mensaje AMQP tras POST              | routing key `cliente.created` + JSON `CLIENTE_CREATED` |
| 3  | `GET /api/clientes/{id}`            | 200 + datos                                         |
| 4  | `GET /api/clientes`                 | 200 + array con el cliente                          |
| 5  | `PUT /api/clientes/{id}`            | 200 + datos actualizados                            |
| 6  | Mensaje AMQP tras PUT               | routing key `cliente.updated` + JSON `CLIENTE_UPDATED` |
| 7  | `POST` con `nombre` vacío           | 400                                                 |
| 8  | `POST` sin `telefono`               | 400                                                 |
| 9  | `GET /api/clientes/999999999`       | 404                                                 |
| 10 | `PUT /api/clientes/999999999`       | 404                                                 |
| 11 | `DELETE /api/clientes/{id}`         | 204                                                 |
| 12 | Mensaje AMQP tras DELETE            | routing key `cliente.deleted` + JSON `CLIENTE_DELETED` |
| 13 | `DELETE /api/clientes/999999999`    | 404                                                 |
| 14 | `GET /api/clientes` sin JWT         | 401                                                 |

## Verificación RabbitMQ

Los tests abren una conexión AMQP real y declaran una **cola temporal** (server-named,
exclusiva) bindeada a `cliente.exchange` con routing key `cliente.#` **antes** de las
operaciones de escritura. Como en un topic exchange los mensajes sin cola bindeada se
pierden, esta cola garantiza capturar los eventos. Por cada POST/PUT/DELETE busca un
mensaje cuya routing key y cuyo cuerpo JSON (`eventType`, `clienteId`, `nombre`,
`telefono`, `occurredAt`) coincidan con el enunciado.

## Reporte

Al finalizar la suite se genera:

```
target/exam-report/resultado-examen.json
```

Incluye `examStudentId`, `examMachineId` (hostname) y `machineFingerprint`.
Resultado `APROBADO` cuando los 14 tests pasan.

## Nota

Si `cliente-service` aún no existe, no publica en RabbitMQ, o usa otro exchange/routing
key/JSON, los tests de mensajes (**2, 6 y 12**) fallarán aunque el CRUD HTTP funcione.
