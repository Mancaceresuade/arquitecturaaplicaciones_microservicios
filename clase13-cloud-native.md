# Clase 13 — Cloud Native, Serverless y Costos: Estimación TCO/ROM

Guía práctica para la clase del 01/06. El objetivo es explorar qué significa llevar el
proyecto base a la nube: qué servicios reemplazan a cada componente local, cuánto cuesta
en un escenario mínimo, y cómo estimar el TCO del PoC con las calculadoras oficiales.

---

## Temas de la clase

| Tema | Herramienta / Concepto |
|------|------------------------|
| Cloud Native | 12-Factor App, contenedores, microservicios |
| Serverless | AWS Lambda / Azure Functions / GCP Cloud Run |
| Managed Services | RDS, Amazon MQ, CloudWatch vs self-hosted |
| Práctica | Calculadoras de costos (AWS / Azure / GCP) |
| Costos | TCO, ROM, estimación del PoC |

---

## Requerimientos

- Navegador web (para las calculadoras, no se requiere cuenta)
- El proyecto base levantado (opcional, para tener contexto de los componentes)
- Acceso a internet para las calculadoras oficiales

---

## Arquitectura del proyecto base (referencia)

```
API Gateway (8080)
    ├── Auth Service (8083)
    ├── Inventory Service (8082)
    └── Proveedor Service (8084?)

Infraestructura de soporte:
    Config Server (8888)
    Eureka Server (8761)
    RabbitMQ / Kafka
    Zipkin (9411)
    ELK Stack (opcional)
```

---

## Parte 1 — Cloud Native y Mapeo a Servicios Cloud

### Los principios 12-Factor que ya cumple el proyecto

El proyecto base ya aplica varios principios de la metodología [12-Factor App](https://12factor.net/):

| Factor | Nombre | ¿El proyecto lo cumple? |
|--------|--------|-------------------------|
| III | Config | ✅ Config Server externaliza la configuración |
| VI | Processes | ✅ Servicios stateless (sesión en JWT, no en servidor) |
| IX | Disposability | ✅ Spring Boot arranca rápido, shutdown limpio |
| XI | Logs | ✅ Logs como streams a stdout (Logback) |
| IV | Backing Services | ⚠️ H2 en memoria (dev) — en prod necesita cambio a Postgres/RDS |

### Mapeo del proyecto base a servicios cloud

| Componente Local | AWS | Azure | GCP |
|-----------------|-----|-------|-----|
| Spring Boot Services | ECS Fargate | Container Apps | Cloud Run |
| Eureka Server | App Mesh / ECS SD | Service Connector | Cloud Service Mesh |
| Config Server | Parameter Store / Secrets Manager | App Configuration | Secret Manager |
| RabbitMQ | Amazon MQ | Service Bus | Pub/Sub |
| H2 / PostgreSQL | RDS (PostgreSQL) | Azure SQL | Cloud SQL |
| Zipkin | AWS X-Ray | Application Insights | Cloud Trace |
| ELK Stack | CloudWatch Logs | Azure Monitor | Cloud Logging |

> **Nota clave:** Eureka no tiene equivalente directo en la nube. En ECS/EKS/Cloud Run el
> service discovery está integrado en la plataforma. Migrar a la nube implica eliminar Eureka
> y usar el mecanismo nativo del proveedor.

---

## Parte 2 — Serverless: ¿Cuándo usarlo con microservicios?

### ¿Qué es Serverless (FaaS)?

Serverless = Functions as a Service. El código corre en respuesta a un evento. No hay
servidor que gestionar: el proveedor asigna recursos bajo demanda y factura por ejecución.

**Servicios:**
- **AWS Lambda**: máx. 15 min por invocación, 10 GB de memoria, hasta 1000 instancias concurrentes por defecto.
- **Azure Functions**: similar a Lambda, con triggers via HTTP, Queue, Timer, Event Hub.
- **GCP Cloud Functions / Cloud Run**: Cloud Functions para eventos simples; Cloud Run para contenedores completos (más parecido a nuestros microservicios).

### ¿Cuándo usar Serverless con el proyecto base?

Serverless no reemplaza a todos los microservicios — es ideal para tareas específicas:

```
✅ Buen fit para Serverless:
   - Procesador de eventos de RabbitMQ/Kafka (ProductCreatedEvent)
   - Generación de reportes bajo demanda (trigger HTTP, larga latencia aceptable)
   - Notificaciones por email/SMS al crear un producto
   - Cleanup de caché o datos temporales (cron trigger)

❌ Mal fit para Serverless:
   - API Gateway (necesita baja latencia constante, warm instances)
   - Auth Service (el cold start agrega latencia inaceptable en login)
   - Inventory Service (estado interno, DB connection pool)
```

### Cold Start: el problema principal

Un **cold start** ocurre cuando Lambda necesita inicializar una nueva instancia. Para
aplicaciones Spring Boot el cold start puede ser de **5-15 segundos** debido al tiempo de
inicio de la JVM y el contexto de Spring.

Soluciones:
- **Provisioned Concurrency** (AWS): mantiene instancias calientes — costo adicional.
- **GraalVM Native Image**: compila la aplicación a binario nativo, elimina el cold start.
- **Micronaut / Quarkus**: frameworks diseñados para arranque en milisegundos.

---

## Parte 3 — Práctica: Estimación de Costos con las Calculadoras

### Acceso a las calculadoras

| Proveedor | URL |
|-----------|-----|
| **AWS** | https://calculator.aws/pricing/2/estimator |
| **Azure** | https://azure.microsoft.com/pricing/calculator/ |
| **GCP** | https://cloud.google.com/products/calculator |

### Configuración base para estimar el PoC

El proyecto tiene **5 servicios Spring Boot** que necesitan cómputo permanente:
- API Gateway
- Auth Service
- Inventory Service
- Proveedor Service
- Config Server

(Eureka se elimina en la nube — se reemplaza por el mecanismo nativo)

#### Recursos mínimos por servicio (tier de desarrollo)

```
CPU:    0.25 vCPU
RAM:    512 MB
Horas:  730 h/mes (24x7)
```

#### Paso a paso en AWS Calculator (ECS Fargate)

1. Ir a https://calculator.aws/pricing/2/estimator
2. Buscar "Fargate"
3. Agregar tarea:
   - Operating system: Linux
   - CPU: 0.25 vCPU
   - Memory: 0.5 GB
   - Number of tasks: 1
   - Duration: 730 hours/month
4. Repetir para los 5 servicios (o configurar "5 tasks" si son idénticas)
5. Agregar **RDS** (t3.micro, PostgreSQL, Single-AZ):
   - Database engine: PostgreSQL
   - Instance class: db.t3.micro
   - Deployment: Single-AZ
6. Agregar **Amazon MQ** (mq.t3.micro):
   - Broker engine: RabbitMQ
   - Instance type: mq.t3.micro
   - Single-instance broker
7. Agregar **CloudWatch Logs**:
   - Ingest: ~1 GB/mes
   - Storage: ~5 GB/mes (retención 30 días)

---

## Observar 1 — Resultado de la estimación AWS

Después de configurar los ítems anteriores, el estimador muestra:

| Componente | Costo mensual estimado (USD) |
|------------|------------------------------|
| ECS Fargate (5 servicios) | ~$30-45 |
| RDS t3.micro (PostgreSQL) | ~$25-30 |
| Amazon MQ mq.t3.micro | ~$15-20 |
| CloudWatch Logs | ~$3-5 |
| **Total infraestructura** | **~$73-100** |

> Los precios varían por región. Usar **us-east-1** para obtener los precios más bajos de AWS.

---

## Parte 4 — TCO y ROM del PoC

### ¿Qué es TCO (Total Cost of Ownership)?

El TCO es el costo total de poseer y operar un sistema durante su vida útil. Incluye más
que solo la infraestructura:

```
TCO = Infraestructura + Operación + Desarrollo

Infraestructura:  cómputo, almacenamiento, red (egress), licencias
Operación:        monitoreo, backups, parches, soporte, on-call, incidentes
Desarrollo:       migración inicial, adaptación, capacitación, deuda técnica
```

**Regla práctica:** los costos de operación suelen ser **1.5x–2x** el costo de infraestructura
pura en proyectos de mediana escala.

### ROM (Rough Order of Magnitude)

El ROM es una estimación de alto nivel con margen de error del **±50%**. Es suficiente para
tomar decisiones arquitectónicas iniciales sin invertir tiempo en una estimación detallada.

#### Estimación ROM del PoC (AWS, us-east-1)

```
Infraestructura:
  ECS Fargate (5 servicios × 0.25 vCPU × 0.5 GB)  →  $30-45 /mes
  RDS t3.micro (PostgreSQL)                         →  $25-30 /mes
  Amazon MQ mq.t3.micro (RabbitMQ)                 →  $15-20 /mes
  CloudWatch Logs + X-Ray básico                   →   $5-8 /mes
  ──────────────────────────────────────────────────────────────
  Subtotal infraestructura:                            $75-103 /mes

Operación (×1.5):                                   ~$112-154 /mes

ROM Total:                                          ~$187-257 /mes
Redondeado: ~USD 200-260 /mes
```

#### Comparativa: ¿conviene la nube vs VM propia?

| Escenario | Costo estimado | Observaciones |
|-----------|---------------|---------------|
| Cloud (AWS Fargate) | ~$200-260 /mes | Sin capex, escalado automático, sin ops |
| VPS único (DigitalOcean/Linode) | ~$40-80 /mes | Requiere ops manual, riesgo de single point of failure |
| VM propia (on-prem) | ~$0 variable + $150-300 /mes ops | Si el HW ya existe; alto costo de operación |

> **Para un PoC**: la nube es la opción más práctica. El costo operativo de gestionar
> infraestructura propia supera el costo de la nube para equipos pequeños sin DevOps dedicado.

---

## Observar 2 — Comparar dos proveedores cloud

Usar las calculadoras de **Azure** y **GCP** con la misma configuración que se usó en AWS
y registrar los resultados:

```bash
# Configuración equivalente en Azure (Container Apps):
# 5 contenedores × 0.25 vCPU × 0.5 GB × 730 hs
# + Azure SQL (Basic tier)
# + Service Bus (Basic tier)
# + Azure Monitor (básico)

# Configuración equivalente en GCP (Cloud Run):
# 5 servicios × 0.25 vCPU × 0.5 GB
# + Cloud SQL (db-f1-micro)
# + Pub/Sub (por mensajes)
# + Cloud Logging (ingesta básica)
```

Registrar los tres costos y responder:
1. ¿Qué proveedor resulta más barato para este PoC?
2. ¿Qué servicio tiene el mayor impacto en el costo total?
3. ¿Cómo cambia el costo si el tráfico se multiplica por 10?

---

## Observar 3 — Escenario de autoescalado

En ECS Fargate se puede configurar **Auto Scaling** basado en CPU o memory utilization.
Estimar el costo si el sistema escala de 1 a 3 instancias de Inventory Service durante
4 horas al día:

```
Costo adicional por autoescalado:
  2 instancias extra × 0.25 vCPU × 0.5 GB × 4 hs × 30 días
  ≈ 2 × $0.04048/vCPU-hora × 0.25 × 4 × 30
  ≈ $2.43 /mes adicionales (insignificante al escenario mínimo)
```

> El autoescalado en Fargate tiene costo casi despreciable para este volumen. El verdadero
> costo aparece cuando se escalan servicios con DB connection pools — la base de datos se
> convierte en el bottleneck y hay que escalar también RDS.

---

## Parte 5 — Decisión Make vs Buy

### ¿Cuándo tiene sentido la nube?

```
✅ La nube conviene cuando:
   - El equipo no tiene expertise en operaciones (DevOps/SRE)
   - El volumen de tráfico es impredecible
   - Se necesita velocidad de deploy (minutos vs días)
   - Es un PoC o MVP que puede escalar o descartarse

⚠️ La nube puede no convenir cuando:
   - El volumen es alto y predecible (economía de escala on-prem)
   - Regulaciones exigen que los datos no salgan de tus servidores
   - Ya existe infraestructura propia amortizada
   - El equipo tiene DevOps dedicado

❌ Evitar la nube si:
   - El proveedor no tiene SLA adecuado para el dominio (ej: salud, defensa)
   - El vendor lock-in es inaceptable estratégicamente
```

### Vendor Lock-in: el riesgo a largo plazo

Algunos servicios generan dependencia difícil de revertir:
- **Alta dependencia**: AWS Lambda (código con SDK de AWS), DynamoDB (API propietaria), Azure Service Bus (API propietaria)
- **Baja dependencia**: ECS/Cloud Run (Docker estándar), RDS/Cloud SQL (PostgreSQL estándar), Amazon MQ (protocolo AMQP estándar)

El proyecto base usa **RabbitMQ sobre AMQP** y **PostgreSQL** — ambos estándares abiertos.
Migrar entre proveedores cloud o de cloud a on-prem es relativamente sencillo.

---

## Resumen: de localhost a la nube

| Etapa | Qué cambia | Qué se mantiene igual |
|-------|------------|----------------------|
| Dev → Cloud Dev | Eureka se elimina, Config Server pasa a Parameter Store | Código de los servicios sin cambios |
| Cloud Dev → Staging | H2 reemplazado por RDS, RabbitMQ por Amazon MQ | application.yml con env vars |
| Staging → Prod | Auto Scaling, Multi-AZ RDS, CloudWatch alerts | Imágenes Docker idénticas |

La arquitectura hexagonal del proyecto facilita la migración: los adaptadores de infraestructura
(repositorios, publishers de eventos) son los únicos que cambian. La lógica de negocio no se toca.

---

## Recursos

- [AWS Pricing Calculator](https://calculator.aws/pricing/2/estimator)
- [Azure Pricing Calculator](https://azure.microsoft.com/pricing/calculator/)
- [GCP Pricing Calculator](https://cloud.google.com/products/calculator)
- [The Twelve-Factor App](https://12factor.net/)
- [CNCF Cloud Native Definition](https://github.com/cncf/toc/blob/main/DEFINITION.md)
- [AWS Fargate Pricing](https://aws.amazon.com/fargate/pricing/)
- [Spring Boot on AWS Lambda (Spring Cloud Function)](https://spring.io/blog/2023/01/27/spring-cloud-function-and-aws-lambda)
