# exam-proveedor-integration-tests

Pruebas oficiales del ejercicio descrito en `parcial2_tema1_enunciado.md`.

## Requisitos previos

Antes de ejecutar `verify`, deben estar en marcha:

| Componente          | Puerto |
|---------------------|--------|
| Zookeeper / KRaft   | 2181   |
| Kafka Broker        | 9092   |
| `eureka-server`     | 8761   |
| `auth-service`      | 8083   |
| `api-gateway`       | 8080   |
| `proveedor-service` | 8086   |

El gateway debe enrutar `Path=/api/proveedores/**` → `lb://proveedor-service`.
El topic `proveedor.events` debe existir (creación previa o `auto.create.topics.enable=true`).

## Comando

Desde la raíz del repositorio:

```bash
mvn -pl exam-proveedor-integration-tests verify
```

Para compilar sin ejecutar integración:

```bash
mvn -pl exam-proveedor-integration-tests verify -DskipITs
```

## Variables de entorno

| Variable                  | Por defecto             |
|---------------------------|-------------------------|
| `GATEWAY_BASE_URL`        | `http://127.0.0.1:8080` |
| `EXAM_STUDENT_ID`         | `NO-ASIGNADO`           |
| `KAFKA_BOOTSTRAP_SERVERS` | `127.0.0.1:9092`        |

Configurar legajo antes de correr:

```bash
export EXAM_STUDENT_ID=tu-legajo
```

## Casos cubiertos

| #  | Test                                   | Verifica                                                |
|----|----------------------------------------|---------------------------------------------------------|
| 1  | `POST /api/proveedores`                | 201 + id + body                                         |
| 2  | Evento Kafka tras POST                 | key `proveedor.created` + JSON `PROVEEDOR_CREATED`      |
| 3  | `GET /api/proveedores/{id}`            | 200 + datos                                             |
| 4  | `GET /api/proveedores`                 | 200 + array con el proveedor                            |
| 5  | `PUT /api/proveedores/{id}`            | 200 + datos actualizados                                |
| 6  | Evento Kafka tras PUT                  | key `proveedor.updated` + JSON `PROVEEDOR_UPDATED`      |
| 7  | `POST` con `nombre` vacío              | 400                                                     |
| 8  | `POST` sin `telefono`                  | 400                                                     |
| 9  | `GET /api/proveedores/999999999`       | 404                                                     |
| 10 | `PUT /api/proveedores/999999999`       | 404                                                     |
| 11 | `DELETE /api/proveedores/{id}`         | 204                                                     |
| 12 | Evento Kafka tras DELETE               | key `proveedor.deleted` + JSON `PROVEEDOR_DELETED`      |
| 13 | `DELETE /api/proveedores/999999999`    | 404                                                     |
| 14 | `GET /api/proveedores` sin JWT         | 401                                                     |

## Verificación Kafka

Los tests abren un `KafkaConsumer` real sobre `proveedor.events`. El consumidor se posiciona
al **final del log** al iniciar (`auto.offset.reset=latest`), así solo ve los eventos producidos
durante la corrida. Por cada operación de escritura busca un registro cuya `key` y cuyo `value`
JSON (`eventType`, `proveedorId`, `nombre`, `telefono`, `occurredAt`) coincidan con el enunciado.

## Reporte

Al finalizar la suite se genera:

```
target/exam-report/resultado-examen.json
```

Resultado `APROBADO` cuando los 14 tests pasan.

## Nota

Si `proveedor-service` aún no existe, no publica en Kafka, o usa otra key/topic/JSON,
los tests de eventos (**2, 6 y 12**) fallarán aunque el CRUD HTTP funcione.
