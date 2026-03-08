# Ecosistema de Microservicios - Spring Boot 3.4 / Java 21

Arquitectura de microservicios con Spring Boot 3.4, Java 21 y Maven Multi-módulo.

> **Dos opciones de ejecución:** 
> - **Docker (recomendado):** Fácil, ~4-6GB RAM, incluye todo automatizado
> - **Manual (ahorro de memoria):** Instalaciones nativas, ~1.5-3GB RAM, configuración manual

## Estructura de Módulos

| Módulo | Puerto | Descripción |
|--------|--------|-------------|
| **config-server** | 8888 | Configuración centralizada (filesystem) |
| **eureka-server** | 8761 | Service Discovery |
| **auth-service** | 8083 | Servicio de Identidad (JWT, BCrypt, H2) |
| **api-gateway** | 8080 | Spring Cloud Gateway - OAuth2 Resource Server |
| **inventory-service** | 8082 | Microservicio de inventario protegido (H2) |
| **notification-service** | 8084 | Consumidor de eventos (RabbitMQ o Kafka) |
| **RabbitMQ** | 5672 / 15672 | Message Broker opción 1 (management UI en 15672) |
| **Apache Kafka** | 9092 | Message Broker opción 2 (KRaft, sin Zookeeper) |
| **Zipkin** | 9411 | Distributed Tracing UI |
| **Elasticsearch** | 9200 | Almacenamiento y búsqueda de logs (Docker/local) |
| **Logstash** | 5044 | Recolección y parseo de logs (instalación local) |
| **Kibana** | 5601 | UI para visualizar logs (Docker/local) |

## Requisitos Previos

### Para ejecución con Docker (recomendado):
- Java 21
- Maven 3.9+
- [Docker Desktop](https://www.docker.com/products/docker-desktop/)

### Para ejecución manual (sin Docker):
- Java 21
- Maven 3.9+
- [Erlang/OTP](https://www.erlang.org/downloads) (para RabbitMQ)
- [RabbitMQ](https://www.rabbitmq.com/download.html) o [Apache Kafka](https://kafka.apache.org/downloads)
- [Elasticsearch](https://www.elastic.co/downloads/elasticsearch) (opcional)
- [Kibana](https://www.elastic.co/downloads/kibana) (opcional)

## Arranque con Docker (recomendado)

> **Importante:** Docker Desktop debe estar corriendo antes de ejecutar estos comandos.
> Verificá que el ícono en la bandeja del sistema no diga "starting...".

**Con RabbitMQ (por defecto):**
```bash
docker compose --profile rabbitmq up --build
```

**Con Apache Kafka:**
```bash
docker compose --profile kafka up --build
```

Esto levanta todos los servicios (incluyendo Elasticsearch y Kibana para ELK) en el orden correcto gracias a los `depends_on` y healthchecks configurados en `docker-compose.yml`. El perfil elegido determina qué broker y qué adaptadores de mensajería se usan.

> **Nota:** Logstash debe ejecutarse por separado desde tu instalación local de Windows (ver sección ELK).

Para detener los servicios, presioná `Ctrl + C` en la terminal donde está corriendo y luego:

```bash
docker compose --profile rabbitmq down
# o
docker compose --profile kafka down
```

> `Ctrl + C` detiene los contenedores. `docker compose down` además los elimina junto con la red creada.

## Arranque manual (sin Docker)

Si tienes poca memoria RAM, puedes ejecutar todo sin Docker usando instalaciones nativas. Requiere más configuración inicial pero consume menos recursos.

### Requisitos previos

- Java 21 instalado
- Maven 3.9+
- Todas las herramientas instaladas localmente (ver instrucciones abajo)

### Instalación de dependencias sin Docker

#### 1. RabbitMQ (Opción 1 - Broker ligero)

1. **Descargar e instalar Erlang:**
   - Ir a https://www.erlang.org/downloads
   - Descargar el instalador OTP para Windows
   - Instalar (se instala en `C:\Program Files\erl-XX.X\`)

2. **Descargar e instalar RabbitMQ:**
   - Ir a https://www.rabbitmq.com/download.html
   - Descargar "RabbitMQ Server" para Windows
   - Instalar (se instala en `C:\Program Files\RabbitMQ Server\`)
   - Agregar al PATH: `C:\Program Files\RabbitMQ Server\rabbitmq_server-X.X.X\sbin\`

3. **Habilitar Management Plugin:**
   ```cmd
   rabbitmq-plugins enable rabbitmq_management
   ```

4. **Iniciar servicio:**
   ```cmd
   rabbitmq-server start
   ```

5. **Verificar:** http://localhost:15672 (guest/guest)

#### 2. Apache Kafka (Opción 2 - Broker más completo)

1. **Descargar Kafka:**
   ```powershell
   # Crear directorio
   mkdir C:\kafka
   cd C:\kafka

   # Descargar (versión 3.6.0)
   Invoke-WebRequest -Uri "https://downloads.apache.org/kafka/3.6.0/kafka_2.13-3.6.0.tgz" -OutFile "kafka.tgz"
   ```

2. **Descomprimir:**
   ```cmd
   cd C:\kafka
   tar -xzf kafka.tgz
   cd kafka_2.13-3.6.0
   ```

3. **Configurar Zookeeper (necesario para Kafka tradicional):**
   - Editar `config\zookeeper.properties`
   - Cambiar `dataDir=/tmp/zookeeper` por `dataDir=C:/kafka/zookeeper-data`

4. **Configurar Kafka:**
   - Editar `config\server.properties`
   - Cambiar `log.dirs=/tmp/kafka-logs` por `log.dirs=C:/kafka/kafka-logs`

5. **Crear directorios:**
   ```cmd
   mkdir C:\kafka\zookeeper-data
   mkdir C:\kafka\kafka-logs
   ```

6. **Iniciar Zookeeper (en terminal 1):**
   ```cmd
   cd C:\kafka\kafka_2.13-3.6.0
   bin\windows\zookeeper-server-start.bat config\zookeeper.properties
   ```

7. **Iniciar Kafka (en terminal 2):**
   ```cmd
   cd C:\kafka\kafka_2.13-3.6.0
   bin\windows\kafka-server-start.bat config\server.properties
   ```

8. **Verificar:**
   ```cmd
   cd C:\kafka\kafka_2.13-3.6.0
   bin\windows\kafka-topics.bat --bootstrap-server localhost:9092 --list
   ```

#### 3. Zipkin (Distributed Tracing)

1. **Descargar JAR ejecutable:**
   ```powershell
   mkdir C:\zipkin
   cd C:\zipkin
   Invoke-WebRequest -Uri "https://zipkin.io/quickstart.sh" -OutFile "start-zipkin.bat"
   # O descargar directamente el JAR desde https://search.maven.org/remote_content?g=io.zipkin.java&a=zipkin-server&v=LATEST&c=exec
   ```

2. **Alternativa: descargar JAR directamente:**
   ```powershell
   Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/io/zipkin/zipkin-server/3.2.0/zipkin-server-3.2.0-exec.jar" -OutFile "zipkin.jar"
   ```

3. **Crear script de inicio:**
   ```cmd
   echo java -jar zipkin.jar > start-zipkin.bat
   ```

4. **Ejecutar:**
   ```cmd
   cd C:\zipkin
   start-zipkin.bat
   ```

5. **Verificar:** http://localhost:9411

#### 4. Elasticsearch (Stack ELK)

Ya tienes información detallada en la sección ELK arriba. Descargar desde https://www.elastic.co/downloads/elasticsearch

#### 5. Kibana (Stack ELK)

1. **Descargar Kibana:**
   - Ir a https://www.elastic.co/downloads/kibana
   - **Importante:** Descargar la misma versión que Elasticsearch (8.17.0) para evitar problemas de compatibilidad
   - Elegir el `.zip` para Windows

2. **Descomprimir:**
   ```cmd
   # Crear directorio
   mkdir C:\kibana
   cd C:\kibana

   # Descomprimir (ajusta el nombre del archivo)
   tar -xzf kibana-8.17.0-windows-x86_64.zip
   cd kibana-8.17.0-windows-x86_64
   ```

3. **Configurar conexión a Elasticsearch:**
   - Editar `config\kibana.yml`
   - Agregar/modificar estas líneas:
   ```yaml
   # Conexión a Elasticsearch local
   elasticsearch.hosts: ["http://localhost:9200"]

   # Puerto de Kibana
   server.port: 5601

   # Host (para acceso desde navegador)
   server.host: "localhost"

   # Idioma (opcional)
   i18n.locale: "es"
   ```

4. **Crear script de inicio (opcional):**
   ```cmd
   # Crear script para facilitar el inicio
   echo cd C:\kibana\kibana-8.17.0-windows-x86_64 && bin\kibana.bat > start-kibana.bat
   ```

5. **Ejecutar Kibana:**
   ```cmd
   # Opción 1: Directo
   cd C:\kibana\kibana-8.17.0-windows-x86_64
   bin\kibana.bat

   # Opción 2: Usando el script
   start-kibana.bat
   ```

5. **Verificar:** Abrir http://localhost:5601 en el navegador

> **Nota:** Kibana puede tardar 30-60 segundos en cargar completamente la primera vez.

#### Configuración inicial de Kibana (después de instalar):

1. **Abrir Kibana:** http://localhost:5601

2. **Configurar Index Pattern:**
   - Ir a **Management → Stack Management → Index Patterns**
   - Hacer clic en **Create index pattern**
   - En "Index pattern name" escribir: `logs-*`
   - En "Timestamp field" seleccionar `@timestamp`
   - Hacer clic en **Create index pattern**

3. **Campos importantes para filtrar:**
   - `service`: Nombre del microservicio (auth-service, api-gateway, etc.)
   - `level`: Nivel de log (ERROR, WARN, INFO, DEBUG)
   - `traceId`: Para correlacionar con Zipkin
   - `message`: Contenido del log

4. **Ir a Discover:**
   - Seleccionar el index pattern `logs-*`
   - Ahora podrás ver y buscar en los logs

## 🤖 Machine Learning en Kibana

Kibana incluye **Elastic Machine Learning** para detección automática de anomalías, útil para tu proyecto de microservicios:

### Aplicaciones prácticas:

1. **Detección de anomalías en logs:**
   - Alertas cuando un servicio genera más errores de lo normal
   - Identificación de picos de latencia inusuales
   - Detección de comportamiento anormal en requests

2. **Monitoreo proactivo:**
   - Predecir cuándo un servicio puede fallar
   - Alertas sobre degradación gradual del rendimiento
   - Identificación de memory leaks

3. **Análisis de patrones:**
   - Comportamiento inusual de usuarios
   - Cambios en patrones de uso
   - Correlación entre eventos distribuidos

### ¿Puedes usarlo?

**Sí, con limitaciones:**
- **Versión gratuita (Basic):** Funciones básicas de ML incluidas
- **Licencia Gold/Platinum:** ML avanzado (requiere pago)
- **En desarrollo:** Perfecto para pruebas y aprendizaje

### Cómo probarlo:
1. Ve a **Machine Learning** en el menú lateral de Kibana
2. Crea un job de anomaly detection en tus índices de logs
3. Configura alertas basadas en anomalías detectadas

#### Optimización de memoria para Kibana:
- Si tienes poca RAM, edita `config\kibana.yml` y agrega:
```yaml
# Reducir intervalo de operaciones (menos CPU)
ops.interval: 5000

# Limitar payload máximo
server.maxPayloadBytes: 1048576

# Deshabilitar telemetry
telemetry.optIn: false
```
   - Kibana consume aproximadamente 200-400MB de RAM en configuración básica.
   - **Espacio en disco:** ~900MB-1.1GB descomprimido (incluye Node.js embebido + librerías frontend).

#### 6. Logstash (Stack ELK)

Ya instalado en `C:\Program Files\logstash-8.17.0\`. Usar el pipeline del proyecto.

### Orden de arranque completo (sin Docker)

1. **Broker de mensajería** (elegir uno):
   - **RabbitMQ:** Ejecutar `rabbitmq-server start`
   - **Kafka:** Iniciar Zookeeper → Iniciar Kafka

2. **Zipkin** (opcional para tracing):
   ```cmd
   cd C:\zipkin && start-zipkin.bat
   ```

3. **Stack ELK** (opcional para logs centralizados):
   - **Elasticsearch:** `C:\elasticsearch\bin\elasticsearch.bat`
   - **Logstash:** `cd "C:\Program Files\logstash-8.17.0" && bin\logstash.bat -f "C:\ruta\a\tu\proyecto\logstash\pipeline.conf"`
   - **Kibana:** `C:\kibana\bin\kibana.bat`

4. **Servicios Spring Boot** (en terminales separadas):
   ```bash
   # Terminal 1 - Config Server
   cd config-server && mvn spring-boot:run

   # Terminal 2 - Eureka
   cd eureka-server && mvn spring-boot:run

   # Terminal 3 - Auth Service
   cd auth-service && mvn spring-boot:run

   # Terminal 4 - API Gateway
   cd api-gateway && mvn spring-boot:run

   # Terminal 5 - Inventory Service (elegir profile)
   cd inventory-service && mvn spring-boot:run -Dspring-boot.run.profiles=rabbitmq
   # o para Kafka:
   # cd inventory-service && mvn spring-boot:run -Dspring-boot.run.profiles=kafka

   # Terminal 6 - Notification Service (mismo profile)
   cd notification-service && mvn spring-boot:run -Dspring-boot.run.profiles=rabbitmq
   # o para Kafka:
   # cd notification-service && mvn spring-boot:run -Dspring-boot.run.profiles=kafka
   ```

### Optimización de memoria

Para minimizar uso de RAM:

1. **Elasticsearch:** Editar `config\jvm.options` y reducir heap:
   ```
   -Xms256m
   -Xmx512m
   ```

2. **Kibana:** Configurar para menor uso de memoria editando `config\kibana.yml`:
   ```yaml
   # Reducir intervalo de operaciones (menos CPU)
   ops.interval: 5000

   # Limitar conexiones
   server.maxPayloadBytes: 1048576

   # Deshabilitar telemetry si no lo necesitas
   telemetry.optIn: false
   ```

3. **Cerrar herramientas no necesarias:** Si no usas tracing, no levantes Zipkin. Si no usas logs centralizados, no levantes ELK.

4. **Usar solo RabbitMQ:** Es más liviano que Kafka + Zookeeper.

### Verificación final (después del arranque manual)

Después de levantar todos los servicios, verifica que todo esté funcionando:

#### 1. **Servicios Spring Boot:**
```bash
# Verificar que Eureka detecte todos los servicios
curl http://localhost:8761/eureka/apps

# Verificar health de cada servicio
curl http://localhost:8080/actuator/health  # API Gateway
curl http://localhost:8083/actuator/health  # Auth Service
curl http://localhost:8082/actuator/health  # Inventory Service
curl http://localhost:8084/actuator/health  # Notification Service
```

#### 2. **Broker de mensajería:**
```bash
# RabbitMQ
curl http://localhost:15672/api/overview -u guest:guest

# Kafka (si usas Kafka)
cd C:\kafka\kafka_2.13-3.6.0
bin\windows\kafka-topics.bat --bootstrap-server localhost:9092 --list
```

#### 3. **Stack ELK:**
```bash
# Elasticsearch
curl http://localhost:9200/_cluster/health

# Kibana
curl http://localhost:5601/api/status

# Verificar que Logstash esté procesando logs (debería mostrar "Pipeline started")
```

#### 4. **Zipkin (opcional):**
```bash
curl http://localhost:9411/api/v2/services
```

#### 5. **Prueba completa del sistema:**
```bash
# 1. Obtener token
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | jq -r '.token')

# 2. Crear producto (genera logs en todos los servicios)
curl -X POST http://localhost:8080/api/inventory/products -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":"Producto Test","quantity":10,"price":99.99}'

# 3. Ver logs en Kibana (http://localhost:5601)
# 4. Ver trazas en Zipkin (http://localhost:9411)
```

### Resumen de consumo de memoria aproximado (configuración optimizada):

| Componente | Memoria RAM | Notas |
|------------|-------------|-------|
| **RabbitMQ** | 100-200MB | Ligero, recomendado |
| **Kafka + Zookeeper** | 400-600MB | Más pesado |
| **Elasticsearch** | 256-512MB | Configurado para desarrollo |
| **Logstash** | 100-200MB | Procesamiento ligero |
| **Kibana** | 200-400MB RAM<br/>900MB-1.1GB disco | UI web |
| **Zipkin** | 100-200MB | Opcional |
| **Spring Boot services** | 300-500MB | 4 servicios ~150MB cada uno |
| **TOTAL aproximado** | 1.5-3GB | Dependiendo de qué componentes actives |

> **Recomendación:** Para 8GB de RAM total, usa RabbitMQ + ELK sin Zipkin. Para 4GB, solo RabbitMQ + servicios básicos.

## Usuarios de Prueba (Auth Service)

| Usuario | Password | Roles |
|---------|----------|-------|
| admin | admin123 | ADMIN, USER |
| user | user123 | USER |

## Comandos cURL

### 1. Obtener Token JWT (Login)

**Linux / macOS / Git Bash:**
```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

**Windows CMD:**
```cmd
curl -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123\"}"
```

**Windows PowerShell:**
```powershell
$response = Invoke-RestMethod -Uri "http://localhost:8080/auth/login" -Method POST -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}'
$response.token
```

Respuesta esperada:
```json
{
  "token": "eyJhbGciOiJIUzM4NCJ9...",
  "type": "Bearer",
  "expiresIn": 3600
}
```

### 2. Probar Endpoint Protegido (con Token)

Guarda el token en una variable y úsalo en el header `Authorization`:

**Linux / macOS / Git Bash:**
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.token')

curl -X GET http://localhost:8080/api/inventory/products \
  -H "Authorization: Bearer $TOKEN"
```

**Windows CMD:**
```cmd
curl -X GET http://localhost:8080/api/inventory/products -H "Authorization: Bearer TOKEN_AQUI"
```
> Reemplazá `TOKEN_AQUI` por el token obtenido en el paso 1.

**Windows PowerShell:**
```powershell
$response = Invoke-RestMethod -Uri "http://localhost:8080/auth/login" -Method POST -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}'
$token = $response.token
Invoke-RestMethod -Uri "http://localhost:8080/api/inventory/products" -Headers @{ Authorization = "Bearer $token" }
```

### 3. Probar sin Token (debe fallar con 401)

**Linux / macOS / Git Bash:**
```bash
curl -X GET http://localhost:8080/api/inventory/products
```

**Windows CMD:**
```cmd
curl -X GET http://localhost:8080/api/inventory/products
```

### 4. Crear Producto (con Token)

**Linux / macOS / Git Bash:**
```bash
curl -X POST http://localhost:8080/api/inventory/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Monitor","quantity":15,"price":299.99}'
```

**Windows CMD:**
```cmd
curl -X POST http://localhost:8080/api/inventory/products -H "Authorization: Bearer TOKEN_AQUI" -H "Content-Type: application/json" -d "{\"name\":\"Monitor\",\"quantity\":15,\"price\":299.99}"
```

**Windows PowerShell:**
```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/inventory/products" -Method POST -ContentType "application/json" -Headers @{ Authorization = "Bearer $token" } -Body '{"name":"Monitor","quantity":15,"price":299.99}'
```

## Consola H2

Cada microservicio con persistencia tiene su **propia base de datos H2 en memoria**:

- **Auth Service**: http://localhost:8083/h2-console  
  - JDBC URL: `jdbc:h2:mem:authdb`
  
- **Inventory Service**: http://localhost:8082/h2-console  
  - JDBC URL: `jdbc:h2:mem:inventorydb`

Usuario: `sa` | Password: (vacío)

> **Importante**: Si accedes a la consola del auth-service, no verás las tablas del inventory-service. Son bases de datos separadas.

## Configuración JWT

La clave secreta se define en `application.yml` de cada servicio que la usa:

```yaml
jwt:
  secret: MiClaveSecretaParaJWT_MuyLarga_Y_Segura_123456789
  expiration-ms: 3600000  # 1 hora (solo auth-service)
```

**Debe ser la misma** en auth-service, api-gateway e inventory-service.

## Seguridad

- **BCrypt** para hash de contraseñas (auth-service)
- **JWT** generado con jjwt (HS384)
- **Gateway**: WebFlux Security, Lambda DSL, validación JWT en cada petición
- **GatewayFilter**: Propaga el header `Authorization` a los servicios downstream
- **Inventory Service**: OAuth2 Resource Server, valida JWT recibido del Gateway

---

## Arquitectura Hexagonal (inventory-service)

El `inventory-service` fue refactorizado de una arquitectura clásica en capas a **Arquitectura Hexagonal (Puertos y Adaptadores)**.

### Estructura ANTES (capas tradicionales)

```
inventory-service/src/main/java/com/uade/inventory/
├── InventoryServiceApplication.java
├── config/
│   ├── DataInitializer.java
│   └── SecurityConfig.java
├── controller/
│   └── InventoryController.java        ← Accede directo al Repository
├── entity/
│   └── Product.java                    ← Entidad JPA (dominio acoplado a JPA)
└── repository/
    └── ProductRepository.java          ← Spring Data JPA
```

**Problemas de esta estructura:**
- El controlador accede directamente al repositorio JPA, sin capa de negocio intermedia.
- La entidad de dominio (`Product`) está acoplada a JPA (`@Entity`, `@Table`, `@Id`).
- Si se quisiera cambiar la base de datos (ej: de H2 a MongoDB), habría que modificar el dominio y el controlador.
- No hay separación clara entre lógica de negocio e infraestructura.

### Estructura DESPUÉS (hexagonal)

```
inventory-service/src/main/java/com/uade/inventory/
├── InventoryServiceApplication.java
│
├── domain/                              ← NÚCLEO (sin dependencias de frameworks)
│   ├── model/
│   │   └── Product.java                ← POJO puro, sin anotaciones JPA
│   └── port/
│       ├── in/
│       │   └── ProductUseCase.java      ← Puerto de ENTRADA (interfaz)
│       └── out/
│           └── ProductRepositoryPort.java  ← Puerto de SALIDA (interfaz)
│
├── application/                         ← CASOS DE USO
│   └── service/
│       └── ProductService.java          ← Implementa ProductUseCase
│                                           usando ProductRepositoryPort
│
└── infrastructure/                      ← ADAPTADORES (frameworks, BD, HTTP)
    ├── adapter/
    │   ├── in/web/
    │   │   └── InventoryController.java ← Adaptador REST (usa ProductUseCase)
    │   └── out/persistence/
    │       ├── ProductJpaEntity.java     ← Entidad JPA (solo infraestructura)
    │       ├── ProductJpaRepository.java ← Spring Data JPA
    │       ├── ProductPersistenceAdapter.java ← Implementa ProductRepositoryPort
    │       └── ProductMapper.java        ← Mapea domain ↔ JPA
    └── config/
        ├── SecurityConfig.java
        └── DataInitializer.java
```

### Principios aplicados

| Concepto | Cómo se aplica |
|----------|---------------|
| **Dominio puro** | `Product.java` es un POJO sin `@Entity`. No conoce JPA ni Spring. |
| **Puerto de entrada** | `ProductUseCase` define qué operaciones ofrece el dominio. |
| **Puerto de salida** | `ProductRepositoryPort` define qué necesita el dominio de persistencia. |
| **Adaptador de entrada** | `InventoryController` recibe HTTP y delega al caso de uso. |
| **Adaptador de salida** | `ProductPersistenceAdapter` implementa el puerto usando JPA. |
| **Mapper** | `ProductMapper` traduce entre `Product` (dominio) y `ProductJpaEntity` (JPA). |
| **Inversión de dependencias** | El dominio define interfaces; la infraestructura las implementa. |

### Beneficio clave

Si en el futuro se quisiera reemplazar H2/JPA por MongoDB, solo habría que:
1. Crear un nuevo `ProductMongoAdapter` que implemente `ProductRepositoryPort`.
2. No se toca ni el dominio, ni los casos de uso, ni el controlador.

---

## Comunicación Asincrónica - Event-Driven Architecture

El ecosistema implementa comunicación asincrónica entre microservicios con soporte para **RabbitMQ** y **Apache Kafka**, seleccionables mediante Spring Profiles.

### Selección del broker

| Comando Docker | Broker | Spring Profile |
|---------------|--------|---------------|
| `docker compose --profile rabbitmq up --build` | RabbitMQ | `rabbitmq` |
| `docker compose --profile kafka up --build` | Apache Kafka (KRaft) | `kafka` |

El cambio de broker **no requiere modificar código**. Solo se cambia el perfil.

### Flujo del evento

```
┌──────────────────┐    POST /products    ┌──────────────────────┐
│   API Gateway    │ ──────────────────── │   inventory-service   │
│   (puerto 8080)  │                      │    (puerto 8082)      │
└──────────────────┘                      └──────────┬───────────┘
                                                     │
                                            1. Guarda en H2
                                            2. Publica evento
                                                     │
                                          ┌──────────▼───────────┐
                                          │   Message Broker      │
                                          │                       │
                                          │ ┌─────────────────┐   │
                                          │ │ Profile:rabbitmq │   │
                                          │ │ Exchange+Queue   │   │
                                          │ └─────────────────┘   │
                                          │        O              │
                                          │ ┌─────────────────┐   │
                                          │ │ Profile: kafka   │   │
                                          │ │ Topic:           │   │
                                          │ │ product-created  │   │
                                          │ └─────────────────┘   │
                                          └──────────┬───────────┘
                                                     │
                                            Consume evento
                                                     │
                                          ┌──────────▼───────────┐
                                          │ notification-service  │
                                          │   (puerto 8084)       │
                                          │                       │
                                          │ Loguea la notificación│
                                          │ del nuevo producto    │
                                          └──────────────────────┘
```

### Componentes involucrados

**inventory-service (productor):**

| Archivo | Rol | Profile |
|---------|-----|---------|
| `domain/event/ProductCreatedEvent.java` | Evento de dominio | todos |
| `domain/port/out/EventPublisherPort.java` | Puerto de salida (interfaz) | todos |
| `infrastructure/adapter/out/messaging/RabbitMQPublisherAdapter.java` | Adaptador RabbitMQ | `rabbitmq` |
| `infrastructure/adapter/out/messaging/RabbitMQConfig.java` | Exchange, Queue, Binding | `rabbitmq` |
| `infrastructure/adapter/out/messaging/KafkaPublisherAdapter.java` | Adaptador Kafka | `kafka` |
| `infrastructure/adapter/out/messaging/KafkaConfig.java` | Producer config | `kafka` |

**notification-service (consumidor):**

| Archivo | Rol | Profile |
|---------|-----|---------|
| `event/ProductCreatedEvent.java` | DTO del evento recibido | todos |
| `listener/ProductEventListener.java` | Listener `@RabbitListener` | `rabbitmq` |
| `config/RabbitMQConfig.java` | Exchange, Queue, Binding | `rabbitmq` |
| `listener/KafkaProductEventListener.java` | Listener `@KafkaListener` | `kafka` |
| `config/KafkaConfig.java` | Consumer config | `kafka` |

### Cómo probarlo

Al crear un producto (paso 4 de los comandos cURL), se publica automáticamente un evento al broker activo. En los logs del `notification-service` vas a ver:

**Con RabbitMQ:**
```
=== NOTIFICACIÓN RECIBIDA ===
Nuevo producto creado:
  ID:       4
  Nombre:   Monitor
  ...
=============================
```

**Con Kafka:**
```
=== NOTIFICACIÓN RECIBIDA (Kafka) ===
Nuevo producto creado:
  ID:       4
  Nombre:   Monitor
  ...
=====================================
```

### Consolas de administración

- **RabbitMQ Management**: http://localhost:15672 (guest / guest)
- **Kafka**: No tiene UI incluida. Podés ver los topics con:

```bash
docker exec -it <kafka-container> kafka-topics.sh --bootstrap-server localhost:9092 --list
```

### Integración con la arquitectura hexagonal

El evento se publica desde la capa de **aplicación** (`ProductService`) a través de un **puerto de salida** (`EventPublisherPort`). Los adaptadores concretos viven en **infraestructura** y se activan según el profile:

```
EventPublisherPort (interfaz del dominio)
    ├── RabbitMQPublisherAdapter  → @Profile("rabbitmq")
    └── KafkaPublisherAdapter     → @Profile("kafka")
```

Esto demuestra un beneficio real de la hexagonal: el dominio y los casos de uso no saben ni les importa si el mensaje viaja por RabbitMQ o por Kafka. La decisión es puramente de infraestructura.

---

## Observabilidad - Distributed Tracing con Zipkin

Todos los servicios del ecosistema reportan trazas distribuidas a **Zipkin** usando **Micrometer Tracing** (reemplazo de Spring Cloud Sleuth en Spring Boot 3.x).

### Qué es y para qué sirve

Cuando un request viaja por múltiples servicios (gateway → auth/inventory → notification), cada servicio genera **spans** que se agrupan bajo un mismo **traceId**. Zipkin los recolecta y permite visualizar la cadena completa, identificar cuellos de botella y diagnosticar errores.

### Stack utilizado

| Componente | Rol |
|-----------|-----|
| **Micrometer Tracing** | API de tracing para Spring Boot 3.x |
| **Brave** (`micrometer-tracing-bridge-brave`) | Implementación del bridge de tracing |
| **Zipkin Reporter** (`zipkin-reporter-brave`) | Envía los spans al servidor Zipkin |
| **Zipkin Server** | Recolecta, almacena y visualiza las trazas |

### Configuración aplicada a cada servicio

```yaml
management:
  tracing:
    sampling:
      probability: 1.0       # 100% de los requests se tracean (dev)
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans

logging:
  pattern:
    correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}] "
```

- `probability: 1.0` — En producción se baja a 0.1 (10%) para no impactar performance.
- `correlation` — Agrega el nombre del servicio, traceId y spanId a cada línea de log.

### Servicios que reportan trazas

| Servicio | Rol en la traza |
|----------|----------------|
| `api-gateway` | Primer span — recibe el request del cliente |
| `auth-service` | Span de autenticación (login) |
| `inventory-service` | Span de negocio (CRUD de productos) |
| `notification-service` | Span de consumo de eventos |

### UI de Zipkin

- URL: http://localhost:9411

Desde ahí podés:
1. Buscar trazas por servicio, duración o traceId.
2. Ver el diagrama de dependencias entre servicios.
3. Analizar la latencia de cada span en la cadena.

### Ejemplo de traza completa

Al crear un producto con token JWT, la traza en Zipkin muestra:

```
[api-gateway]  ────────────────────────────────────────────────
   │
   ├── [inventory-service]  ───────────────────────────────────
   │       │
   │       └── [notification-service]  ── (evento asincrónico)
   │
   └── duración total: ~120ms
```

### Correlación en logs

En la consola de cada servicio, los logs incluyen traceId y spanId:

```
[api-gateway,6b3e44e01a7d4c00,6b3e44e01a7d4c00] GET /api/inventory/products
[inventory-service,6b3e44e01a7d4c00,a1b2c3d4e5f60000] Returning 3 products
```

El mismo `traceId` (`6b3e44e...`) aparece en todos los servicios que participaron del request, permitiendo correlacionar logs distribuidos.

---

## Centralización de Logs - Stack ELK (Elasticsearch + Logstash + Kibana)

Complementa a Zipkin: mientras Zipkin muestra *cómo fluye un request* entre servicios (trazas), ELK muestra *qué pasó dentro de cada servicio* (logs completos, errores, datos de negocio).

### Componentes

| Componente | Puerto | Función | Configuración |
|-----------|--------|---------|---------------|
| **Elasticsearch** | 9200 | Almacena y permite buscar logs | Docker (configurado en `docker-compose.yml`) |
| **Logstash** | 5044 | Recibe logs de los servicios, los parsea y los envía a Elasticsearch | Instalación local (Windows) |
| **Kibana** | 5601 | UI web para buscar, filtrar y visualizar logs | Docker (configurado en `docker-compose.yml`) |

### Arranque con Docker (recomendado)

Elasticsearch y Kibana están incluidos en `docker-compose.yml` y se levantan automáticamente junto con los servicios Spring Boot:

```bash
# Con RabbitMQ
docker compose --profile rabbitmq up elasticsearch kibana -d

# Con Kafka
docker compose --profile kafka up elasticsearch kibana -d
```

> **Nota:** Elasticsearch tiene configuración optimizada para desarrollo (512MB heap, sin seguridad) y healthchecks configurados.

### Configuración de Logstash (instalación local)

#### Requisitos previos

- Java 21 (ya instalado para el proyecto)
- Logstash descargado (ya tienes `C:\Program Files\logstash-8.17.0\`)

#### Pipeline configurado

El proyecto incluye un pipeline completo en `logstash/pipeline.conf`:

```bash
# Ejecutar Logstash con el pipeline del proyecto
cd "C:\Program Files\logstash-8.17.0"
bin\logstash.bat -f "C:\Users\Usuario\Documents\uade\2026\arquitecturadeaplicaciones\microservicios\logstash\pipeline.conf"
```

**Características del pipeline:**
- Recibe logs JSON via TCP en puerto 5044
- Extrae `traceId`, `spanId` y `service` del MDC para correlación con Zipkin
- Filtra logs de healthchecks y métricas para no sobrecargar ELK
- Crea índices diarios (`logs-YYYY.MM.dd`)
- Compatible con la configuración de desarrollo (sin SSL)

#### Orden de arranque completo

1. **Docker services:** `docker compose --profile rabbitmq up elasticsearch kibana -d`
   - Esperar a que Elasticsearch responda en http://localhost:9200
   - Esperar a que Kibana cargue en http://localhost:5601

2. **Logstash local:** Ejecutar el comando anterior
   - Esperar a que muestre `Pipeline started` en consola

3. **Servicios Spring Boot:**
   ```bash
   docker compose --profile rabbitmq up config-server eureka-server auth-service api-gateway inventory-service-rabbitmq notification-service-rabbitmq
   ```

### Integración con los microservicios

Cada servicio está configurado para enviar logs en formato JSON a Logstash por TCP. La configuración incluye:

1. **Dependencia Maven** — `logstash-logback-encoder` agregada a todos los servicios (`auth-service`, `api-gateway`, `inventory-service`, `notification-service`)

2. **Configuración Logback** — Archivo `src/main/resources/logback-spring.xml` en cada servicio con:
   - Appender TCP que envía logs JSON a `localhost:5044`
   - Formato de consola que incluye `[servicio,traceId,spanId]`
   - Perfiles Spring configurados (dev/prod)

### Flujo de logs

```
Servicios Spring Boot
    │
    │  logs JSON via TCP (puerto 5044)
    │  incluye: traceId, spanId, service, level, message
    │
    ▼
Logstash (localhost:5044)  ──────►  Elasticsearch (localhost:9200)
    │                                        (índices diarios: logs-YYYY.MM.dd)
    └── pipeline.conf: parsea JSON,
        extrae campos, filtra healthchecks
    │
    ▼
Kibana (localhost:5601)
    │
    └── UI para buscar, filtrar y visualizar logs
```

### Configuración inicial de Kibana

Después de levantar todos los servicios:

1. Abrir **http://localhost:5601**
2. Ir a **Management → Stack Management → Index Patterns**
3. Crear pattern: `logs-*` (coincide con los índices diarios)
4. Configurar `@timestamp` como campo de tiempo
5. Campos útiles para filtrar:
   - `service`: nombre del microservicio (auth-service, api-gateway, etc.)
   - `level`: ERROR, WARN, INFO, DEBUG
   - `traceId`: correlacionar con Zipkin
   - `logger`: clase/paquete que generó el log

### Correlación con Zipkin

Los logs incluyen `traceId` y `spanId` gracias a la configuración de Micrometer Tracing. Esto permite:

- **Zipkin** → Vista de trazas (latencia, dependencias entre servicios)
- **Kibana** → Vista de logs detallados (errores, warnings, datos de negocio)

**Ejemplo de uso:**
1. En Zipkin, buscar un request problemático y copiar su `traceId`
2. En Kibana, filtrar por ese `traceId` para ver todos los logs relacionados
3. Combinar ambas herramientas para debugging completo

### Consideraciones de recursos

- **Elasticsearch**: Configurado con 512MB heap para desarrollo
- **Logstash**: Ligero, procesa logs en tiempo real
- **Kibana**: UI web, consume recursos moderados
- **Recomendación**: Mínimo 8GB RAM para todo el ecosistema

### Cómo probar que ELK funciona

1. **Verificar servicios corriendo:**
   ```bash
   # Elasticsearch
   curl http://localhost:9200/_cluster/health

   # Kibana (debe responder 200)
   curl -f http://localhost:5601/api/status
   ```

2. **Generar logs de prueba:**
   - Ejecutar un request con token JWT (ver sección de comandos cURL)
   - Crear un producto nuevo para generar evento asincrónico
   - Verificar que aparezcan logs en las consolas de los servicios

3. **Ver logs en Kibana:**
   - Ir a **Discover** en Kibana
   - Seleccionar index pattern `logs-*`
   - Filtrar por `service:inventory-service` para ver logs de un servicio específico
   - Buscar por `traceId` para correlacionar con Zipkin

**Ejemplo de búsqueda en Kibana:**
- `service:api-gateway AND level:INFO` → logs del gateway
- `traceId:abc123*` → todos los logs de un request específico
- `message:*error* OR message:*exception*` → logs de error

### Troubleshooting

**Si no aparecen logs en Kibana:**
- Verificar que Logstash muestre "Pipeline started" en consola
- Revisar que los servicios Spring Boot estén enviando logs (deben aparecer en sus consolas)
- Esperar 1-2 minutos para que Elasticsearch indexe los logs
- Verificar conectividad: `telnet localhost 5044`

**Si Elasticsearch no inicia:**
```bash
# Ver logs del contenedor
docker logs <elasticsearch-container-id>

# Resetear si es necesario
docker-compose down elasticsearch
docker volume rm microservicios_elasticsearch-data
docker-compose up elasticsearch -d
```

**Si Logstash no conecta a Elasticsearch:**
- Verificar que Elasticsearch esté healthy: `curl http://localhost:9200/_cluster/health`
- Revisar la configuración en `logstash/pipeline.conf`
- Verificar que no haya firewall bloqueando el puerto 9200
