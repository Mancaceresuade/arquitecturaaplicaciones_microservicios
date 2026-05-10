# exam-proveedor-kafka-integration-tests

Pruebas oficiales del ejercicio descrito en `ejercicio-como-parcial-proveedor-kafka.md`.

## Requisitos previos

Antes de ejecutar `verify`, deben estar en marcha (entre otros) **Eureka**, **Auth**, **API Gateway**, **Apache Kafka** (broker accesible en los bootstrap servers configurados) y el microservicio **`proveedor-service`** registrado en Eureka, con la ruta del gateway `/api/proveedores/**` configurada.

### Topic Kafka

Los tests consumen del topic **`proveedor.events`** con un grupo de consumidor distinto por ejecución. El broker debe tener ese topic creado con antelación **o** tener habilitada la creación automática de topics (`auto.create.topics.enable=true` en desarrollo). Una partición suele bastar para el orden de este examen.

## Comando

Desde la raíz del repositorio:

```bash
mvn -pl exam-proveedor-kafka-integration-tests verify
```

Para compilar el módulo **sin** ejecutar los tests de integración (por ejemplo en CI sin Kafka):

```bash
mvn -pl exam-proveedor-kafka-integration-tests verify -DskipITs
```

## Variables de entorno

| Variable | Por defecto |
|----------|----------------|
| `GATEWAY_BASE_URL` | `http://127.0.0.1:8080` (si ponés `localhost`, el test lo reescribe a `127.0.0.1` para evitar `ConnectException` por IPv6 en Windows) |
| `EXAM_STUDENT_ID` | `NO-ASIGNADO` |
| `KAFKA_BOOTSTRAP_SERVERS` | `127.0.0.1:9092` si no está definido; las apariciones de `localhost` en la cadena se normalizan a `127.0.0.1` |

## Nota

Si el módulo `proveedor-service` aún no publica a Kafka según el contrato del enunciado, estos tests **fallarán** hasta que exista el servicio, el gateway enrute correctamente y se publiquen los eventos con topic **`proveedor.events`**, keys **`proveedor.created` / `proveedor.updated` / `proveedor.deleted`** y el JSON indicado.

### `java.net.ConnectException` en el login

Eso significa que **no se pudo abrir TCP al API Gateway** (por defecto `http://127.0.0.1:8080`). Comprobá que **api-gateway** esté en marcha **después** de Eureka. Si usás otra URL, exportá `GATEWAY_BASE_URL` (sin barra final). Los tests de Failsafe arrancan con `-Djava.net.preferIPv4Stack=true` para reducir problemas de `localhost` → `::1`.

### Errores de conexión a Kafka

Comprobá que los bootstrap servers sean alcanzables desde la misma máquina donde corre Maven (`KAFKA_BOOTSTRAP_SERVERS`). En **WSL2** con broker en Windows, si no definís la variable, el test intenta usar la IP del host Windows (misma heurística que el módulo Rabbit) con puerto **9092**.

### Maven en WSL2 y servicios en Windows

`export KAFKA_BOOTSTRAP_SERVERS=$(grep nameserver /etc/resolv.conf | head -1 | awk '{print $2}'):9092`

(o la IP que corresponda al host donde escucha Kafka).
