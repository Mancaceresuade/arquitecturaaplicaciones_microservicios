# exam-pedido-integration-tests

Pruebas oficiales del ejercicio descrito en `parcial2.md`.

## Requisitos previos

Antes de ejecutar `verify`, deben estar en marcha:

| Componente       | Puerto |
|------------------|--------|
| Zookeeper        | 2181   |
| Kafka Broker     | 9092   |
| `eureka-server`  | 8761   |
| `auth-service`   | 8083   |
| `api-gateway`    | 8080   |
| `pedido-service` | 8087   |

El gateway debe enrutar `Path=/api/pedidos/**` → `lb://pedido-service`.

## Comando

Desde la raíz del repositorio:

```bash
mvn -pl exam-pedido-integration-tests verify
```

Para compilar sin ejecutar integración:

```bash
mvn -pl exam-pedido-integration-tests verify -DskipITs
```

## Variables de entorno

| Variable           | Por defecto             |
|--------------------|-------------------------|
| `GATEWAY_BASE_URL` | `http://127.0.0.1:8080` |
| `EXAM_STUDENT_ID`  | `NO-ASIGNADO`           |

Configurar legajo antes de correr:

```bash
export EXAM_STUDENT_ID=tu-legajo
```

## Casos cubiertos

| # | Test                                      | Verifica                              |
|---|-------------------------------------------|---------------------------------------|
| 1 | `POST /api/pedidos`                       | 201 + id + estado `PENDIENTE`         |
| 2 | `GET /api/pedidos/{id}` inmediato         | 200 + estado `PENDIENTE`              |
| 3 | `GET /api/pedidos/{id}` con espera        | 200 + estado `CONFIRMADO` (Kafka)     |
| 4 | `GET /api/pedidos`                        | 200 + array con el pedido             |
| 5 | `POST` con `descripcion` vacía            | 400                                   |
| 6 | `POST` con `cantidad` = 0                 | 400                                   |
| 7 | `GET /api/pedidos/999999999`              | 404                                   |
| 8 | `GET /api/pedidos` sin JWT                | 401                                   |

## Reporte

Al finalizar la suite se genera:

```
target/exam-report/resultado-examen.json
```

Resultado `APROBADO` cuando los 8 tests pasan.

## Nota

Si `pedido-service` aún no existe o Kafka no está implementado, el **test 3** fallará (el pedido queda en `PENDIENTE`).
