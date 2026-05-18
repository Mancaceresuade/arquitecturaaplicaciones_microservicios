# Clase 11 — Observabilidad: Logs, Métricas y Trazas

Guía práctica para la clase del 18/05. El objetivo es agregar los tres pilares de
observabilidad al `inventory-service` del proyecto base, sin Docker, todo manual.

---

## Qué vamos a construir

| Pilar | Herramienta | Qué vemos al final |
|-------|-------------|---------------------|
| Logs | Logback + MDC | `[traceId, spanId]` en cada línea de consola |
| Métricas | Spring Actuator + Micrometer | Contadores y timers en `/actuator/metrics` |
| Trazas | Micrometer Tracing + Zipkin | Árbol de spans en `localhost:9411` |

> **Nota:** las dependencias necesarias ya están en el `pom.xml` del proyecto.
> No hace falta correr `mvn install` de nada nuevo salvo donde se indique.

---

## Requerimientos

- Java 21+
- Maven 3.8+
- `curl` (o Postman / Bruno)
- Zipkin standalone JAR (ver Paso 0)

---

## Lo que ya tiene el proyecto (no hay que tocar)

- `spring-boot-starter-actuator` en todos los servicios
- `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` en `inventory-service`
- `logstash-logback-encoder` en `inventory-service`
- `logback-spring.xml` en `api-gateway` y `auth-service` (ya muestran `traceId/spanId`)
- Zipkin ya definido en `docker-compose.yml` (lo usamos a mano hoy)

---

## Paso 0 — Descargar y levantar Zipkin

Zipkin tiene un JAR ejecutable oficial. Descargarlo una sola vez:

**Linux / macOS**
```bash
curl -sSL https://zipkin.io/quickstart.sh | bash -s
java -jar zipkin.jar
```

**Windows (PowerShell)**
```powershell
Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/io/zipkin/zipkin-server/3.5.1/zipkin-server-3.5.1-exec.jar" -OutFile zipkin.jar
java -jar zipkin.jar
```

Verificar que está listo: http://localhost:9411

> Dejar esta terminal abierta durante toda la clase.

## Paso 0.1 — Agregar logs en `InventoryController`

Antes de configurar `logback-spring.xml`, instrumentamos el controlador con logs de negocio para ver en consola qué endpoint se invocó y con qué resultado.

**Archivo:**

```
inventory-service/src/main/java/com/uade/inventory/infrastructure/adapter/in/web/InventoryController.java
```

**1. Agregar imports y el logger** (después de los imports existentes, dentro de la clase):

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// ...

private static final Logger log = LoggerFactory.getLogger(InventoryController.class);
```

**2. Agregar logs en cada endpoint** (reemplazar los métodos actuales):

```java
@GetMapping("/products")
public ResponseEntity<List<Product>> getAllProducts() {
    List<Product> products = productUseCase.getAllProducts();
    log.info("GET /api/inventory/products → {} producto(s)", products.size());
    return ResponseEntity.ok(products);
}

@GetMapping("/products/{id}")
public ResponseEntity<Product> getProduct(@PathVariable Long id) {
    log.info("GET /api/inventory/products/{}", id);
    return productUseCase.getProductById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> {
                log.warn("GET /api/inventory/products/{} → 404 Not Found", id);
                return ResponseEntity.notFound().build();
            });
}

@PostMapping("/products")
public ResponseEntity<Product> createProduct(@RequestBody Product product) {
    Product created = productUseCase.createProduct(product);
    log.info("POST /api/inventory/products → id={}, name={}", created.getId(), created.getName());
    return ResponseEntity.ok(created);
}
```

> Reiniciar el `inventory-service` después de guardar. Los logs aparecerán en consola; el `traceId`/`spanId` llegarán en el **Paso 1** con `logback-spring.xml`.

---

## Levantar los microservicios

Abrir una terminal por servicio. Orden obligatorio:

```bash
# Terminal 1 — Config Server (puerto 8888)
cd config-server
mvn spring-boot:run -Dspring-boot.run.profiles=native
# Esperar: "Started ConfigServerApplication"

# Terminal 2 — Eureka Server (puerto 8761)
cd eureka-server
mvn spring-boot:run
# Verificar: http://localhost:8761

# Terminal 3 — Auth Service (puerto 8083)
cd auth-service
mvn spring-boot:run

# Terminal 4 — Inventory Service (puerto 8082)
cd inventory-service
mvn spring-boot:run

# Terminal 5 — API Gateway (puerto 8080)
cd api-gateway
mvn spring-boot:run
```

Usuarios de prueba precargados: `admin / admin123` y `user / user123`.

---

## Observar 1 — Logs sin contexto (estado inicial)

Con todos los servicios levantados, obtener un token y hacer un request:

```bash
# 1. Login (directo al auth-service)
curl -s -X POST http://localhost:8083/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

Copiar el valor del campo `token` de la respuesta. Luego:

```bash
# 2. Consultar inventario (reemplazar TOKEN por el valor copiado)
curl -s http://localhost:8080/api/inventory/products \
  -H "Authorization: Bearer TOKEN"
```

Mirar la consola del `inventory-service`. El log se ve así:

```
2026-05-18 10:15:32.441  INFO 12345 --- [nio-8082-exec-1] c.u.i.i.a.in.web.InventoryController : ...
```

No hay `traceId` ni `spanId`. No podemos correlacionar este log con los logs del
`api-gateway` ni saber a qué request pertenece. **Eso es el problema que vamos a resolver.**

---

## Paso 1 — Crear `logback-spring.xml` en `inventory-service`

El `api-gateway` y el `auth-service` ya tienen este archivo. El `inventory-service` no.

Crear el archivo en:

```
inventory-service/src/main/resources/logback-spring.xml
```

Con el siguiente contenido:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <!-- Consola con traceId y spanId en el patrón -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%property{spring.application.name},%X{traceId},%X{spanId}] %highlight(%-5level) %cyan(%logger{36}) - %msg%n</pattern>
        </encoder>
    </appender>

    <springProfile name="default">
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>

    <springProfile name="dev,development">
        <logger name="com.uade" level="DEBUG"/>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>
</configuration>
```

**Reiniciar el `inventory-service`** (Ctrl+C en la terminal 4 y volver a correr `mvn spring-boot:run`).

---

## Observar 2 — Logs con TraceId y SpanId

Repetir el mismo request de antes:

```bash
curl -s http://localhost:8080/api/inventory/products \
  -H "Authorization: Bearer TOKEN"
```

En la consola del `inventory-service`, en el log del `InventoryController` (no en los DEBUG
de Eureka/Hibernate), deberías ver algo como:

```
2026-05-18 10:16:01.123 [inventory-service,6f4a2b8c1d3e5f7a,3e5f7a6f4a2b8c1d] INFO  c.u.i.i.a.in.web.InventoryController - ...
```

> El primer campo puede salir `null` si Logback no resolvió `spring.application.name`; lo
> importante aquí son `traceId` y `spanId`.

En el `api-gateway`, los logs periódicos de `RouteDefinitionRouteLocator` (refresh de rutas)
suelen mostrar `[null,,]` — **no** son del request. Mirá líneas del mismo segundo que el `curl`.

El **mismo `traceId` en gateway e inventory** se ve de forma fiable después del **Paso 2.2**
(tracing activo en el gateway). Ejemplo:

```
2026-05-18 10:16:01.118 [api-gateway,6f4a2b8c1d3e5f7a,1a2b3c4d5e6f7a8b] INFO  ...
```

> **¿Qué significa esto?**
> - `traceId` — mismo valor en servicios que participan del mismo request HTTP.
> - `spanId` — distinto en cada servicio (cada uno tiene su tramo dentro de la traza).

Con el `traceId` se puede buscar en los logs de todos los servicios todo lo que ocurrió durante un único request HTTP.

---

## Observar 3 — Actuator: health y métricas JVM

`/actuator/**` no requiere autenticación en el `inventory-service` (ver `SecurityConfig.java`).

```bash
# Estado del servicio
curl -s http://localhost:8082/actuator/health

# Lista de todas las métricas disponibles
curl -s http://localhost:8082/actuator/metrics

# Ejemplo: uso de memoria JVM (out-of-the-box, sin escribir código)
curl -s http://localhost:8082/actuator/metrics/jvm.memory.used

# Ejemplo: total de requests HTTP recibidos
curl -s "http://localhost:8082/actuator/metrics/http.server.requests"
```

> Todo esto ya funciona solo con tener `spring-boot-starter-actuator` en el `pom.xml`.
> Micrometer registra automáticamente métricas de JVM, HTTP, pool de threads y más.

---

## Paso 2 — Configurar Zipkin y sampling

El **Paso 1** solo cambia el *formato* de los logs (`traceId`/`spanId` en consola).
El **Paso 2** activa el **tracing** de Micrometer: con qué frecuencia se crean trazas
(sampling) y a dónde se envían (Zipkin).

> **Importante:** al levantar con `mvn spring-boot:run`, el `inventory-service` lee
> su `application.yml` local. **No** consume el config-server en este proyecto.
> El archivo que hay que editar en clase es el de abajo, no solo el del config-server.

**Requisito:** Zipkin corriendo (Paso 0) en http://localhost:9411

### 2.1 — `inventory-service` (obligatorio)

**Archivo:**

```
inventory-service/src/main/resources/application.yml
```

Hoy el proyecto trae tracing **desactivado** (`probability: 0.0`). **Reemplazar** el bloque
`management:` existente (no duplicarlo) por:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  tracing:
    sampling:
      probability: 1.0        # 100% en desarrollo; en producción usar 0.1 (10%)
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```

**Reiniciar el `inventory-service`** (Ctrl+C en su terminal y `mvn spring-boot:run`).

### 2.2 — `api-gateway` (recomendado para correlación en logs y Zipkin)

Sin esto, el inventory puede generar su propia traza por request y el gateway seguirá
mostrando `[null,,]` en muchos logs. Para ver el **mismo `traceId`** en gateway e inventory,
aplicar el mismo cambio en:

```
api-gateway/src/main/resources/application.yml
```

Reemplazar el bloque `management:` (también está en `0.0`) por el mismo bloque del paso 2.1
(sin la sección `zipkin` si preferís no exportar trazas del gateway; en clase conviene
dejarlo igual con `endpoint` a Zipkin).

**Reiniciar el `api-gateway`.**

### 2.3 — Config Server (opcional, solo referencia)

Si más adelante el servicio consumiera config centralizado, el bloque iría en
`config-server/config/inventory-service.yml`. En el repo puede estar ya agregado;
**no alcanza** con editarlo solo ahí si corrés el inventory sin config client.

> **¿Por qué `probability: 1.0`?** En producción con muchos requests no conviene
> enviar el 100% de las trazas a Zipkin (costo de red y almacenamiento). En clase
> usamos 1.0 para que se vea todo.

---

## Observar 4 — Trazas distribuidas en Zipkin

Hacer varios requests para generar trazas:

```bash
# Varios GET
curl -s http://localhost:8080/api/inventory/products -H "Authorization: Bearer TOKEN"
curl -s http://localhost:8080/api/inventory/products -H "Authorization: Bearer TOKEN"

# Un POST para crear un producto
curl -s -X POST http://localhost:8080/api/inventory/products \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Teclado","quantity":10,"price":5500.00}'
```

Abrir http://localhost:9411 y hacer clic en **"Run Query"**.

Cosas para explorar:
- Hacer clic en una traza → ver el árbol de spans (api-gateway → inventory-service)
- Observar la duración de cada span
- Buscar por `serviceName = inventory-service`
- Comparar la duración del POST vs el GET

> **¿Por qué no veo trazas del `auth-service`?** Porque el request de login va
> directo al `auth-service` (puerto 8083), no pasa por el gateway. Las trazas
> del gateway→inventory sí se ven porque ese path usa el gateway como proxy.

---

## Paso 3 — Métricas custom: Counter en `ProductService`

Las métricas JVM son automáticas, pero las métricas de **negocio** hay que
instrumentarlas. Vamos a contar cuántas veces se consultó y cuántos productos se crearon.

Editar `inventory-service/src/main/java/com/uade/inventory/application/service/ProductService.java`:

```java
package com.uade.inventory.application.service;

import com.uade.inventory.domain.event.ProductCreatedEvent;
import com.uade.inventory.domain.model.Product;
import com.uade.inventory.domain.port.in.ProductUseCase;
import com.uade.inventory.domain.port.out.EventPublisherPort;
import com.uade.inventory.domain.port.out.ProductRepositoryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ProductService implements ProductUseCase {

    private final ProductRepositoryPort repositoryPort;
    private final EventPublisherPort eventPublisherPort;

    // ── Métricas ──────────────────────────────────────────────────────────────
    private final Counter productosConsultadosCounter;
    private final Counter productoConsultadoPorIdCounter;
    private final Counter productoCreadoCounter;

    public ProductService(ProductRepositoryPort repositoryPort,
                          EventPublisherPort eventPublisherPort,
                          MeterRegistry meterRegistry) {
        this.repositoryPort = repositoryPort;
        this.eventPublisherPort = eventPublisherPort;

        this.productosConsultadosCounter = Counter.builder("products.consulted")
                .description("Cantidad de veces que se listaron todos los productos")
                .register(meterRegistry);

        this.productoConsultadoPorIdCounter = Counter.builder("products.consulted.by.id")
                .description("Cantidad de consultas por ID")
                .register(meterRegistry);

        this.productoCreadoCounter = Counter.builder("products.created")
                .description("Cantidad de productos creados")
                .register(meterRegistry);
    }

    @Override
    public List<Product> getAllProducts() {
        productosConsultadosCounter.increment();           // ← métrica
        return repositoryPort.findAll();
    }

    @Override
    public Optional<Product> getProductById(Long id) {
        productoConsultadoPorIdCounter.increment();        // ← métrica
        return repositoryPort.findById(id);
    }

    @Override
    public Product createProduct(Product product) {
        Product saved = repositoryPort.save(product);
        productoCreadoCounter.increment();                 // ← métrica
        eventPublisherPort.publishProductCreated(
                new ProductCreatedEvent(saved.getId(), saved.getName(),
                        saved.getQuantity(), saved.getPrice())
        );
        return saved;
    }
}
```

**Reiniciar el `inventory-service`.**

---

## Observar 5 — Métricas de negocio en Actuator

Hacer algunos requests para acumular datos:

```bash
curl -s http://localhost:8080/api/inventory/products -H "Authorization: Bearer TOKEN"
curl -s http://localhost:8080/api/inventory/products -H "Authorization: Bearer TOKEN"
curl -s http://localhost:8080/api/inventory/products -H "Authorization: Bearer TOKEN"
curl -s -X POST http://localhost:8080/api/inventory/products \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Mouse","quantity":5,"price":1200.00}'
```

Consultar las métricas custom:

```bash
# ¿Cuántas veces se listaron todos los productos?
curl -s http://localhost:8082/actuator/metrics/products.consulted | python3 -m json.tool

# ¿Cuántos productos se crearon?
curl -s http://localhost:8082/actuator/metrics/products.created | python3 -m json.tool
```

La respuesta tiene un campo `"value"` que es el contador acumulado. Hacer más requests
y volver a consultar para ver cómo sube.

---

## Paso 4 — Timer: medir latencia del caso de uso (opcional)

Un `Counter` cuenta eventos. Un `Timer` mide cuánto tardó algo.
Agregar un timer al método `getAllProducts`:

```java
// Agregar el import al inicio del archivo:
import io.micrometer.core.instrument.Timer;

// Agregar en el constructor (junto a los counters):
this.getAllProductsTimer = Timer.builder("products.service.latency")
        .description("Latencia del caso de uso getAllProducts")
        .tag("operation", "getAllProducts")
        .register(meterRegistry);

// Declarar el campo a nivel de clase:
private final Timer getAllProductsTimer;

// Modificar el método getAllProducts():
@Override
public List<Product> getAllProducts() {
    productosConsultadosCounter.increment();
    Timer.Sample sample = Timer.start(meterRegistry);    // ← iniciar medición
    List<Product> result = repositoryPort.findAll();
    sample.stop(getAllProductsTimer);                     // ← detener medición
    return result;
}
```

**Reiniciar** y hacer algunos requests. Luego:

---

## Observar 6 — Latencia en Actuator

```bash
curl -s http://localhost:8082/actuator/metrics/products.service.latency | python3 -m json.tool
```

La respuesta incluye:
- `COUNT` — cuántas veces se ejecutó
- `TOTAL_TIME` — tiempo total acumulado
- `MAX` — el request más lento registrado

> Este dato es el que permite detectar degradaciones de performance antes de que
> los usuarios se quejen: si el `MAX` sube de golpe, algo cambió.

---

## Resumen: los tres pilares en el proyecto

| Pilar | Dónde se ve | Qué cambio fue necesario |
|-------|-------------|--------------------------|
| **Logs** | Consola de cada servicio | Crear `logback-spring.xml` con el patrón `%X{traceId},%X{spanId}` |
| **Métricas** | `localhost:8082/actuator/metrics` | Inyectar `MeterRegistry` y registrar `Counter`/`Timer` |
| **Trazas** | `localhost:9411` | Agregar `management.tracing` y `management.zipkin` al yml |

Los tres pilares responden preguntas distintas:
- **Logs** → ¿qué ocurrió exactamente?
- **Métricas** → ¿cómo está el sistema en este momento?
- **Trazas** → ¿dónde tardó este request?

Ninguno reemplaza a los otros. Un sistema observable tiene los tres.

---

---

# Sección 2 — Stack ELK: Logs Centralizados (Manual, sin Docker)

El stack ELK (Elasticsearch + Logstash + Kibana) centraliza los logs de todos los microservicios
en un único lugar con búsqueda full-text. Complementa a Zipkin (trazas) y Actuator (métricas):
aquí se busca *qué ocurrió* en detalle, en todos los servicios a la vez, filtrando por `traceId`.

> **¿Reemplaza a lo anterior?** No. ELK reemplaza la consola como destino de logs; Zipkin y
> Actuator siguen funcionando igual. En producción los logs van a ELK, las trazas a Zipkin,
> las métricas a Prometheus/Grafana (o Actuator directamente).

---

## Versiones del stack ELK (manual)

Usar **la misma versión en los tres componentes** (misma major y minor). En esta guía: **9.4.1**.

| Componente | Versión | Puerto |
|------------|---------|--------|
| Elasticsearch | 9.4.1 | 9200 |
| Logstash | 9.4.1 | 5044 (TCP entrada) |
| Kibana | 9.4.1 | 5601 |

> **Importante:** no mezclar majors (por ejemplo Elasticsearch 9.4 + Logstash 8.17). El output
> de Logstash hacia Elasticsearch suele fallar o no indexar. Si usás Docker, el `docker-compose.yml`
> del repo puede seguir en 8.17.x; para la instalación manual de esta clase usamos **9.4.1**.

---

## Paso 5 — Instalar y levantar Elasticsearch

### Descargar

Ir a https://www.elastic.co/downloads/elasticsearch y descargar el ZIP para tu SO,
o usar los links directos:

**Linux / macOS**
```bash
curl -L -O https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-9.4.1-linux-x86_64.tar.gz
tar -xzf elasticsearch-9.4.1-linux-x86_64.tar.gz
```

**Windows (PowerShell)**
```powershell
Invoke-WebRequest -Uri "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-9.4.1-windows-x86_64.zip" -OutFile elasticsearch.zip
Expand-Archive elasticsearch.zip
# Carpeta resultante: elasticsearch-9.4.1
```

### Deshabilitar seguridad (solo para clase/desarrollo)

Editar `config/elasticsearch.yml` y agregar al final:

```yaml
xpack.security.enabled: false
xpack.security.enrollment.enabled: false
```

### Levantar

**Linux / macOS**
```bash
cd elasticsearch-9.4.1
bin/elasticsearch
```

**Windows (PowerShell)**
```powershell
cd elasticsearch-9.4.1\bin
.\elasticsearch.bat
```

Verificar: http://localhost:9200 debe responder con JSON (`"version" : { "number" : "9.4.1" }`, etc.).

```bash
curl -s http://localhost:9200
curl -s "http://localhost:9200/_cluster/health?pretty"
```

**Windows (sin Python):**
```powershell
Invoke-RestMethod http://localhost:9200
Invoke-RestMethod "http://localhost:9200/_cluster/health?pretty"
```

En `_cluster/health`, buscar `"status" : "green"` o `"yellow"` (en un solo nodo, yellow suele ser normal).

> Dejar esta terminal abierta.

---

## Paso 6 — Instalar y configurar Logstash

### Descargar

**Linux / macOS**
```bash
curl -L -O https://artifacts.elastic.co/downloads/logstash/logstash-9.4.1-linux-x86_64.tar.gz
tar -xzf logstash-9.4.1-linux-x86_64.tar.gz
```

**Windows (PowerShell)**
```powershell
Invoke-WebRequest -Uri "https://artifacts.elastic.co/downloads/logstash/logstash-9.4.1-windows-x86_64.zip" -OutFile logstash.zip
Expand-Archive logstash.zip
# Carpeta resultante: logstash-9.4.1
```

### Crear pipeline de configuración

Dentro de la carpeta de Logstash, crear el archivo `config/microservicios.conf`:

```ruby
input {
  tcp {
    port  => 5044
    codec => json_lines
  }
}

filter {
  # Si el campo "message" tiene JSON anidado (logstash-logback-encoder lo manda como string),
  # intentar parsearlo.
  if [message] {
    json {
      source => "message"
      target => "parsed"
      skip_on_invalid_json => true
    }
  }
}

output {
  elasticsearch {
    hosts    => ["http://localhost:9200"]
    index    => "microservicios-logs-%{+YYYY.MM.dd}"
  }

  # stdout para depuración en clase — ver los eventos en consola de Logstash
  stdout {
    codec => rubydebug
  }
}
```

### Levantar Logstash

**Linux / macOS**
```bash
cd logstash-9.4.1
bin/logstash -f config/microservicios.conf
```

**Windows (PowerShell)**
```powershell
cd logstash-9.4.1\bin
.\logstash.bat -f ..\config\microservicios.conf
```

Esperar hasta ver: `Pipelines running {:count=>1, ...}`. Puede tardar 30-60 segundos.

> Dejar esta terminal abierta.

---

## Paso 7 — Instalar y levantar Kibana

### Descargar

**Linux / macOS**
```bash
curl -L -O https://artifacts.elastic.co/downloads/kibana/kibana-9.4.1-linux-x86_64.tar.gz
tar -xzf kibana-9.4.1-linux-x86_64.tar.gz
```

**Windows (PowerShell)**
```powershell
Invoke-WebRequest -Uri "https://artifacts.elastic.co/downloads/kibana/kibana-9.4.1-windows-x86_64.zip" -OutFile kibana.zip
Expand-Archive kibana.zip
# Carpeta resultante: kibana-9.4.1
```

### Configurar (opcional pero recomendado)

Editar `config/kibana.yml` y asegurarse de que apunte al Elasticsearch local (ya está por defecto):

```yaml
elasticsearch.hosts: ["http://localhost:9200"]
```

### Levantar

**Linux / macOS**
```bash
cd kibana-9.4.1
bin/kibana
```

**Windows (PowerShell)**
```powershell
cd kibana-9.4.1\bin
.\kibana.bat
```

Verificar: http://localhost:5601 (puede tardar ~1 minuto en levantar).

> Dejar esta terminal abierta.

---

## Paso 8 — Configurar `logback-spring.xml` del `inventory-service` para ELK

El `api-gateway` ya tiene el appender Logstash configurado. Copiar ese mismo patrón al
`inventory-service`. Editar (o crear si no existe aún):

```
inventory-service/src/main/resources/logback-spring.xml
```

Reemplazar el contenido con la siguiente versión que incluye tres perfiles Spring:
`default` → solo consola, `dev` → consola con DEBUG, `elk` → consola **y** Logstash TCP:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <!-- ── Appender: consola con traceId/spanId ──────────────────────────── -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%property{spring.application.name},%X{traceId},%X{spanId}] %highlight(%-5level) %cyan(%logger{36}) - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- ── Appender: Logstash TCP (JSON estructurado) ────────────────────── -->
    <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
        <destination>localhost:5044</destination>
        <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
            <providers>
                <timestamp/>
                <logLevel/>
                <loggerName/>
                <message/>
                <mdc/>                  <!-- incluye traceId y spanId del MDC -->
                <stackTrace/>
                <arguments/>
            </providers>
        </encoder>
    </appender>

    <!-- ── Perfil: default (sin argumento -Dspring.profiles.active) ──────── -->
    <springProfile name="default">
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>

    <!-- ── Perfil: dev / development ─────────────────────────────────────── -->
    <springProfile name="dev,development">
        <logger name="com.uade" level="DEBUG"/>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>

    <!-- ── Perfil: elk — activa CONSOLA + LOGSTASH simultáneamente ───────── -->
    <springProfile name="elk">
        <logger name="com.uade" level="DEBUG"/>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
            <appender-ref ref="LOGSTASH"/>
        </root>
    </springProfile>

</configuration>
```

### Reiniciar el `inventory-service` con el perfil `elk`

```bash
# Terminal 4 — detener con Ctrl+C, luego:
cd inventory-service
mvn spring-boot:run -Dspring-boot.run.profiles=elk
```

Verificar en la consola del `inventory-service` que los logs siguen mostrando `traceId/spanId`
y que **la consola de Logstash** también empieza a mostrar eventos JSON (el `stdout rubydebug`
los imprime en tiempo real).

---

## Observar 7 — Buscar logs en Kibana por traceId

### Crear el Data View en Kibana

1. Abrir http://localhost:5601
2. Ir al menú hamburguesa (☰) → **Management** → **Stack Management** → **Data Views**
3. Clic en **Create data view**
4. Name: `Microservicios Logs`
5. Index pattern: `microservicios-logs-*`
6. Timestamp field: `@timestamp`
7. Clic en **Save data view to Kibana**

### Generar tráfico

Primero hacer algunos requests para que haya datos en el índice:

```bash
# Obtener token
curl -s -X POST http://localhost:8083/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# Reemplazar TOKEN por el valor del campo "token"
curl -s http://localhost:8080/api/inventory/products -H "Authorization: Bearer TOKEN"
curl -s http://localhost:8080/api/inventory/products -H "Authorization: Bearer TOKEN"
curl -s -X POST http://localhost:8080/api/inventory/products \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Monitor","quantity":3,"price":45000.00}'
```

### Buscar en Discover

1. Ir a ☰ → **Analytics** → **Discover**
2. Seleccionar el data view `Microservicios Logs`
3. En la barra de búsqueda KQL escribir:

```kql
traceId: "6f4a2b8c1d3e5f7a"
```

(reemplazar por el `traceId` real que aparece en la consola del `inventory-service`)

4. Ver todos los logs de **todos los microservicios** que participaron en ese único request.

### Cosas para explorar en Kibana

- Filtrar por campo: `message: "getAllProducts"`
- Ver campos disponibles en el panel izquierdo (traceId, spanId, level, loggerName)
- Cambiar el rango de tiempo a "Last 15 minutes"
- Comparar el `traceId` que aparece en Kibana con el que aparece en Zipkin para el mismo request

---

## Resumen: ELK vs consola

| | Consola | ELK |
|---|---------|-----|
| **Buscar por traceId** | grep manual en cada servicio | Kibana: un filtro, todos los servicios |
| **Rango de tiempo** | scroll infinito | selector temporal en Kibana |
| **Logs de todos los servicios** | N terminales abiertas | una sola pantalla |
| **Persistencia** | se pierde al reiniciar | índice en Elasticsearch |
| **Costo de setup** | cero | ~5 min (descarga + config) |

> En desarrollo se usa la consola. En staging/producción se usa ELK (o equivalente: Loki,
> Datadog, CloudWatch, etc.). La ventaja clave de ELK es **correlacionar logs de múltiples
> servicios usando el `traceId`** sin abrir N terminales.

---

## Orden de arranque completo (Sección 1 + Sección 2)

```
Terminal 1  → Elasticsearch       (puerto 9200)
Terminal 2  → Logstash             (puerto 5044)
Terminal 3  → Kibana               (puerto 5601)
Terminal 4  → Zipkin               (puerto 9411)
Terminal 5  → Config Server        (puerto 8888)
Terminal 6  → Eureka Server        (puerto 8761)
Terminal 7  → Auth Service         (puerto 8083)
Terminal 8  → Inventory Service    (puerto 8082, perfil elk)
Terminal 9  → API Gateway          (puerto 8080)
```
