# pragma-ms-java-pragma-transfer · ms-transfers

Microservicio de transferencias entre cuentas de **PragBank Perú**. Base: archetype de Pragma `java-webflux-gradle`, con la misma estructura hexagonal y los mismos quality gates.

> **Estado: HU-01 y HU-03 implementadas.** HU-01: transferencias entre cuentas (`openapi/ms-transfers-v1.yaml`), reglas RN-01 a RN-08 ([docs/business-rules.md](docs/business-rules.md)), SAGA débito → crédito con compensación, persistencia DynamoDB (tablas `transfers` e `idempotency`) y eventos SNS según `openapi/transfers-events.asyncapi.yaml`. HU-03: pago a celular (`openapi/ms-transfers-v3.yaml`), reglas RN-09 a RN-13, directorio de alias y tabla `recipient-tokens`; spec, plan y tareas en `specs/003-phone-transfer/`.

## Información general

- **Framework**: Spring Boot 4.x (WebFlux, reactivo, Netty)
- **Lenguaje**: Java 21
- **Build**: Gradle multi-módulo (Kotlin DSL) con version catalog
- **Arquitectura**: hexagonal; el dominio no depende de Spring ni del SDK de AWS (ArchUnit lo verifica)
- **Persistencia**: DynamoDB on-demand con el SDK v2 asíncrono (tablas `transfers`, `idempotency` y `recipient-tokens`)
- **HTTP saliente**: WebClient con timeouts, retry, circuit breaker y bulkhead (resilience4j)
- **Eventos**: SNS `transfers-events` (`openapi/transfers-events.asyncapi.yaml`)
- **Paquete base**: `co.com.pragma.transfers`
- **Cobertura**: gate de 90 % de línea (JaCoCo) enganchado a `check`

## Requisitos y arranque en local

| Herramienta | Versión | Verificar |
| --- | --- | --- |
| Git + llave SSH registrada en `dev.azure.com/your-ado-org` | — | `git clone git@ssh.dev.azure.com:v3/your-ado-org/workshop-peru/pragma-ms-java-pragma-transfer` |
| JDK | 21 (Temurin) | `java -version` |
| Docker Desktop (opcional: WireMock en contenedor) | 4.x | `docker run --rm hello-world` |
| IDE | IntelliJ IDEA o VS Code con Java | — |
| curl o Bruno | — | `curl --version` |

```bash
# 1. Mocks del core bancario, antifraude y directorio (desde la raíz del repo)
docker run --rm -p 9090:8080 -v $PWD/mocks:/home/wiremock wiremock/wiremock:3.9.1

# 2. En otra terminal: compilar y probar
./gradlew build

# 3. Levantar el micro apuntando a los mocks
CORE_BASE_URL=http://localhost:9090 FRAUD_BASE_URL=http://localhost:9090 DIRECTORY_BASE_URL=http://localhost:9090 \
  ./gradlew :application:app-service:bootRun

# 4. Verificar
curl -s http://localhost:8080/actuator/health/liveness
curl -s http://localhost:9090/core/v1/accounts/191-0001234567
```

## API (HU-01)

Contrato: `openapi/ms-transfers-v1.yaml` (Swagger UI en `/swagger-ui.html`). Todas las rutas exigen el header `X-Customer-Id`; sin él responden 401 `UNAUTHORIZED`. `X-Correlation-Id` es opcional: si no llega se genera, se devuelve en la respuesta y se propaga a core, antifraude, logs y eventos.

| Método y ruta | Headers | Respuestas |
| --- | --- | --- |
| `POST /api/v1/transfers` | `X-Customer-Id`, `Idempotency-Key` (UUID, obligatorio) | 201 (`COMPLETED`, `PENDING_REVIEW` o `COMPENSATED`) con `Location`; 200 repetición idempotente; 400, 401, 403, 409, 422, 503 |
| `GET /api/v1/transfers/{transferId}` | `X-Customer-Id` | 200; 403 si es de otro cliente; 404 si no existe |
| `GET /api/v1/accounts/{accountId}/transfers?page=0&size=20&status=&from=&to=` | `X-Customer-Id` | 200 página (más reciente primero, `size` máx. 100); 400; 403 si la cuenta no es del cliente |

### Reglas y orden de evaluación del POST

1. Forma de la petición (400): esquema del contrato, campos desconocidos rechazados, RN-03 monto `> 0` con máximo 2 decimales (`AMOUNT_INVALID`).
2. RN-08 idempotencia: misma llave + mismo cuerpo → 200 con la transferencia original (sin nuevo cargo); cuerpo distinto → 409 `IDEMPOTENCY_KEY_REUSED`. La llave se reserva con escritura condicional y vence a las 24 h.
3. La cuenta origen debe ser del cliente (403 `ACCOUNT_NOT_OWNED`; no se guarda nada).
4. RN-04 máximo por operación (S/ 10,000.00) y RN-05 acumulado diario del cliente (S/ 30,000.00, día de negocio en `America/Lima`; suman RECEIVED, DEBITED, COMPLETED y PENDING_REVIEW).
5. RN-01 cuentas existentes, activas y distintas (`ACCOUNT_INVALID`) y RN-02 misma moneda (`CURRENCY_MISMATCH`).
6. RN-07 antifraude: `< 60` aprueba; `60-79`, timeout (1 s) o caída → `PENDING_REVIEW` sin débito; `>= 80` → 422 `FRAUD_REJECTED`.
7. RN-06 saldo (`INSUFFICIENT_FUNDS`). Va después de antifraude porque `PENDING_REVIEW` no mueve dinero.
8. SAGA: débito (`TRF-{id}-DEBIT`) → crédito (`TRF-{id}-CREDIT`). Si el crédito falla se reversa el débito (`TRF-{id}-REVERSAL`) y la transferencia queda `COMPENSATED` (201, `failureCode: CREDIT_FAILED`).

Los rechazos de los pasos 4 a 7 se guardan como `REJECTED` con su `failureCode` y publican `TransferRejected`; repetir la misma llave devuelve el mismo 422. Si el core no responde (503 `DEPENDENCY_UNAVAILABLE` con `Retry-After`), la transferencia queda en `RECEIVED`/`DEBITED` y el reintento con la misma llave la retoma con los mismos `operationId` (antes consulta `GET /core/v1/operations/{operationId}` para saber si el débito se aplicó).

### Eventos (SNS `transfers-events`)

`TransferCompleted`, `TransferRejected`, `TransferPendingReview` y `TransferCompensated`, con el payload `TransferEvent` del AsyncAPI y los atributos de mensaje `eventType` y `correlationId`. Un fallo al publicar se registra en el log y no revierte la transferencia. Con `SNS_ENABLED=false` los eventos solo se escriben en el log.

### Tablas DynamoDB

| Tabla (variable) | Llave | Índices / TTL |
| --- | --- | --- |
| `transfers` (`DYNAMODB_TABLE_TRANSFERS`) | PK `transferId` (S) | GSI `sourceAccountId-createdAt-index` (PK `sourceAccountId`, SK `createdAt`) para el historial; GSI `customerDay-createdAt-index` (PK `customerDay` = `customerId#yyyy-MM-dd`, SK `createdAt`) para el límite diario |
| `idempotency` (`DYNAMODB_TABLE_IDEMPOTENCY`) | PK `idempotencyKey` (S) | TTL en `expiresAt` (epoch en segundos, 24 h) |

Con `DYNAMODB_ENABLED=false` (local y pruebas) se usan tablas en memoria: el servicio arranca sin AWS, pero los datos no son durables ni compartidos entre réplicas.

### Resiliencia

WebClient con timeout por llamada (core 2 s, antifraude 1 s) más timeouts de conexión y lectura, circuit breaker por dependencia (`coreBanking`, `fraud`; los rechazos de negocio no lo abren) y retry con backoff **solo en lecturas** (`GET` de cuentas y operaciones). Débitos, créditos y reversas no se reintentan a ciegas: los protege su `operationId`.

## Cómo probar la HU-01 en local

1. Levantar los mocks en el puerto 9090 (elige una opción):

   ```bash
   # Con Docker
   docker run --rm -p 9090:8080 -v "$PWD/mocks:/home/wiremock" wiremock/wiremock:3.9.1
   # Sin Docker (solo Java)
   curl -sLO https://repo1.maven.org/maven2/org/wiremock/wiremock-standalone/3.13.2/wiremock-standalone-3.13.2.jar
   java -jar wiremock-standalone-3.13.2.jar --port 9090 --root-dir mocks
   ```

2. Levantar el micro apuntando a los mocks (DynamoDB y SNS quedan en memoria):

   ```bash
   CORE_BASE_URL=http://localhost:9090 FRAUD_BASE_URL=http://localhost:9090 \
     ./gradlew :application:app-service:bootRun
   ```

3. Probar los escenarios (datos de `mocks/README.md`):

   ```bash
   # Transferencia exitosa → 201 COMPLETED
   KEY=$(uuidgen)
   curl -si -X POST http://localhost:8080/api/v1/transfers \
     -H 'Content-Type: application/json' -H 'X-Customer-Id: CUST-000123' -H "Idempotency-Key: $KEY" \
     -d '{"sourceAccountId":"191-0001234567","targetAccountId":"191-0007654321","amount":{"value":"150.00","currency":"PEN"},"description":"Pago alquiler octubre"}'

   # Mismo Idempotency-Key y mismo cuerpo → 200 con la transferencia original (un solo débito)
   # Mismo Idempotency-Key con otro monto → 409 IDEMPOTENCY_KEY_REUSED

   # Antifraude score 67 → 201 PENDING_REVIEW sin débito
   curl -s -X POST http://localhost:8080/api/v1/transfers \
     -H 'Content-Type: application/json' -H 'X-Customer-Id: CUST-000123' -H "Idempotency-Key: $(uuidgen)" \
     -d '{"sourceAccountId":"191-0001234567","targetAccountId":"191-0007654321","amount":{"value":"9500.00","currency":"PEN"}}'

   # Crédito falla → reversa → 201 COMPENSATED
   curl -s -X POST http://localhost:8080/api/v1/transfers \
     -H 'Content-Type: application/json' -H 'X-Customer-Id: CUST-000123' -H "Idempotency-Key: $(uuidgen)" \
     -d '{"sourceAccountId":"191-0001234567","targetAccountId":"191-0009999999","amount":{"value":"200.00","currency":"PEN"}}'

   # Saldo insuficiente (S/ 2,500 disponibles) → 422 INSUFFICIENT_FUNDS; 10000.01 → 422 LIMIT_PER_TX_EXCEEDED

   # Consulta (200 propia, 403 con otro X-Customer-Id, 404 si no existe) e historial paginado
   curl -s http://localhost:8080/api/v1/transfers/<transferId> -H 'X-Customer-Id: CUST-000123'
   curl -s 'http://localhost:8080/api/v1/accounts/191-0001234567/transfers?page=0&size=20' -H 'X-Customer-Id: CUST-000123'
   ```

Las pruebas automáticas (`./gradlew check`) no necesitan Docker: los escenarios de aceptación (`TransfersAcceptanceTest`) levantan el servicio completo con WireMock embebido en la JVM usando los mismos stubs de `mocks/mappings`.

## API (HU-03 · Pago a celular)

Contrato: `openapi/ms-transfers-v3.yaml` (incluye los endpoints de la v1 sin cambios; `openapi/ms-transfers-v1.yaml` se conserva tal cual). SDD: `specs/003-phone-transfer/` (`spec.md`, `plan.md`, `tasks.md`). Mismos headers que la v1 (`X-Customer-Id` obligatorio, `X-Correlation-Id` opcional).

| Método y ruta | Headers | Respuestas |
| --- | --- | --- |
| `GET /api/v1/phone-transfers/recipients?phone=%2B51987654321` | `X-Customer-Id` | 200 `{recipientToken, maskedName, bank, expiresAt}` (sin cuenta ni nombre completo); 400 `PHONE_INVALID`; 401; 404 `RECIPIENT_NOT_FOUND`; 422 `RECIPIENT_NOT_SUPPORTED`; 503 |
| `POST /api/v1/phone-transfers` | `X-Customer-Id`, `Idempotency-Key` (UUID) | 201 con `channel: PHONE` y `targetPhoneMasked` (`+51 9** *** 321`), sin `targetAccountId`; 200 repetición idempotente; 400, 401, 403, 409, 422, 503 |

### Reglas y orden de evaluación

**Resolver destinatario (GET):**

1. RN-09: celular `+51` + 9 dígitos que empiezan en 9 (`^\+519\d{8}$`) → si no, 400 `PHONE_INVALID` (sin llamar al directorio). En el query string el `+` va como `%2B`; si llega sin codificar (espacio) el micro lo interpreta como `+`.
2. RN-10: directorio de alias (`GET /directory/v1/aliases/{phone}`). No afiliado (404) → 404 `RECIPIENT_NOT_FOUND`; otro banco → 422 `RECIPIENT_NOT_SUPPORTED`; PragBank suspendido o sin cuenta → 404.
3. RN-12: se emite un `recipientToken` opaco (`rt_` + 128 bits aleatorios) de un solo uso que vence en 5 minutos. La cuenta destino queda guardada del lado del servidor junto al token; al cliente solo le llega el nombre enmascarado (`Mar*** Qui***`).

**Pagar (POST):**

1. Forma (400): esquema del contrato (`additionalProperties: false`), RN-03 monto (`AMOUNT_INVALID`), `Idempotency-Key` UUID.
2. RN-08 idempotencia (igual que la v1): misma llave + mismo cuerpo → 200 con el pago original aunque el token ya esté usado; cuerpo distinto → 409.
3. RN-12: el token debe existir, ser del mismo cliente, estar vigente (`expiresAt` > ahora) y sin usar → si no, 422 `RECIPIENT_TOKEN_EXPIRED`.
4. La cuenta origen debe ser del cliente → 403 `ACCOUNT_NOT_OWNED`.
5. RN-13: el destinatario no puede ser el mismo cliente (cuenta destino igual a la origen o cuenta del mismo `customerId` en el core) → 422 `SELF_TRANSFER`.
6. RN-11: solo PEN y hasta S/ 500.00 → 422 `LIMIT_PER_TX_EXCEEDED` (detalle "El máximo por pago a celular es S/ 500.00" o "El pago a celular solo admite PEN").
7. Uso único: escritura condicional que marca `usedAt` y `usedByTransferId`. Si otro pago ganó la carrera → 422 `RECIPIENT_TOKEN_EXPIRED`.
8. Flujo de la v1 sin cambios con `channel = PHONE`: RN-04/RN-05, RN-01/RN-02, RN-07 antifraude, RN-06 saldo y SAGA débito → crédito con compensación. Eventos con `channel: PHONE`.

Los rechazos de los pasos 3 a 6 no consumen el token, no guardan transferencia ni publican evento (no se llegó a crear la operación). Un 503 a mitad del flujo deja el token a nombre de la transferencia: el reintento con la misma llave lo vuelve a aceptar aunque ya hayan pasado los 5 minutos.

### Tabla `recipient-tokens`

| Atributo | Tipo | Descripción |
| --- | --- | --- |
| `recipientToken` | S (PK) | Token opaco `rt_…` |
| `customerId` | S | Cliente que resolvió el celular (solo él puede usarlo) |
| `targetAccountId` | S | Cuenta destino resuelta por el directorio (nunca sale del micro) |
| `targetPhoneMasked` | S | `+51 9** *** 321` (no se guarda el celular completo) |
| `maskedName` | S | `Mar*** Qui***` (no se guarda el nombre completo) |
| `createdAt` | S | ISO-8601 UTC |
| `expiresAt` | N | Epoch en segundos; **atributo TTL** de la tabla (5 min) |
| `usedAt` | S | ISO-8601; ausente mientras no se use |
| `usedByTransferId` | S | Transferencia que usó el token |

Condición del uso único: `attribute_exists(recipientToken) AND customerId = :customer AND ((attribute_not_exists(usedAt) AND expiresAt > :now) OR usedByTransferId = :transfer)`. El TTL de DynamoDB puede tardar horas en borrar un ítem vencido; por eso el micro valida `expiresAt` en la lectura y en la condición, sin depender del borrado. La tabla `transfers` agrega el atributo `targetPhoneMasked` (sin índices nuevos).

### Resiliencia del directorio

WebClient propio (`DIRECTORY_BASE_URL`, llave en `X-Api-Key` desde `DIRECTORY_API_KEY`), timeout de 2 s, circuit breaker `directory` y retry con backoff (es una lectura). Si no responde → 503 `DEPENDENCY_UNAVAILABLE` con `Retry-After`.

## Cómo probar la HU-03 en local

Con los mocks en el puerto 9090 y el micro levantado como en la HU-01 (agregando `DIRECTORY_BASE_URL=http://localhost:9090`):

```bash
# 1. Resolver un celular PragBank → 200 con maskedName "Mar*** Qui***" y recipientToken (sin cuenta)
curl -s -G http://localhost:8080/api/v1/phone-transfers/recipients \
  --data-urlencode 'phone=+51987654321' -H 'X-Customer-Id: CUST-000123'
TOKEN=$(curl -s -G http://localhost:8080/api/v1/phone-transfers/recipients \
  --data-urlencode 'phone=+51987654321' -H 'X-Customer-Id: CUST-000123' | sed -E 's/.*"recipientToken":"([^"]+)".*/\1/')

# 2. Pagar → 201 COMPLETED, channel PHONE, targetPhoneMasked "+51 9** *** 321"
curl -si -X POST http://localhost:8080/api/v1/phone-transfers \
  -H 'Content-Type: application/json' -H 'X-Customer-Id: CUST-000123' -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"sourceAccountId\":\"191-0001234567\",\"recipientToken\":\"$TOKEN\",\"amount\":{\"value\":\"45.50\",\"currency\":\"PEN\"},\"description\":\"Almuerzo\"}"

# 3. Repetir el pago con otra llave y el mismo token → 422 RECIPIENT_TOKEN_EXPIRED (uso único)
#    Esperar más de 5 minutos con un token nuevo → 422 RECIPIENT_TOKEN_EXPIRED

# Errores del directorio
curl -s -G http://localhost:8080/api/v1/phone-transfers/recipients --data-urlencode 'phone=987654321'    -H 'X-Customer-Id: CUST-000123' # 400 PHONE_INVALID
curl -s -G http://localhost:8080/api/v1/phone-transfers/recipients --data-urlencode 'phone=+51955555555' -H 'X-Customer-Id: CUST-000123' # 404 RECIPIENT_NOT_FOUND
curl -s -G http://localhost:8080/api/v1/phone-transfers/recipients --data-urlencode 'phone=+51912345678' -H 'X-Customer-Id: CUST-000123' # 422 RECIPIENT_NOT_SUPPORTED

# Pago a mi propio celular (+51999111222 es de la cuenta 191-0001234567) → 422 SELF_TRANSFER
# Monto 500.01 o currency USD con un token válido → 422 LIMIT_PER_TX_EXCEEDED
```

Los escenarios automáticos están en `PhoneTransfersAcceptanceTest` (WireMock embebido con los stubs `mocks/mappings/directory-*.json`; el reloj se adelanta para probar el vencimiento sin esperar).

## Comandos

```bash
./gradlew clean check                       # pruebas, ArchUnit y gate de cobertura (90 %), sin Docker
./gradlew build                             # compila, pruebas, ArchUnit y gate de cobertura
./gradlew jacocoRootReport                  # reporte de cobertura (build/reports/jacoco)
./gradlew :domain:usecases:pitest           # mutation testing del dominio
./gradlew :application:app-service:bootRun  # ejecutar el servicio
```

## Estructura

```
pragma-ms-java-pragma-transfer/
├── domain/
│   ├── model/         # Transfer, estados, excepciones (code/metadata), @Sensitive, phone/ (HU-03)
│   ├── ports/         # Gateways: transferencias, idempotencia, core, antifraude, eventos, directorio, recipient-tokens
│   └── usecases/      # Casos de uso sin Spring (sufijo UseCase)
├── infrastructure/
│   ├── entry-points/reactive-web/     # Router + Handler + RFC 7807 + security headers + validación
│   ├── driven-adapters/
│   │   ├── dynamodb-persistence/      # Tablas transfers, idempotency y recipient-tokens  [dynamodb.enabled]
│   │   ├── external-api/              # WebClient de core bancario, antifraude y directorio de alias
│   │   └── sns-publisher/             # Eventos de dominio a SNS  [sns.enabled]
│   └── helpers/       # CorrelationWebFilter, DataMasker, AesGcmCipher
├── application/app-service/  # MainApplication, config, application.yml, logback-spring.xml
├── openapi/           # Contratos: API propia, core bancario, antifraude y eventos
├── mocks/             # Stubs de WireMock del core y antifraude, y su Dockerfile para ECS
├── deployments/       # azure-pipelines.yml
├── docs/              # ADRs, catálogo de errores, glosario, runbook, threat model
└── specs/             # SDD: spec, plan y tareas por HU (003-phone-transfer)
```

## Documentación de la API (Swagger)

La especificación **se genera desde el código** con springdoc: cada ruta de `TransferRouter` y `PhoneTransferRouter` lleva su `@RouterOperation` (headers, parámetros, cuerpo y respuestas). Las rutas funcionales de WebFlux no se descubren solas, así que **toda ruta nueva debe llevar su `@RouterOperation`**; `OpenApiDocsTest` verifica que aparezcan.

- UI: `http://localhost:8080/swagger-ui.html` → definición *ms-transfers (generado desde el código)*. Botón **Authorize** para `x-api-key`.
- Spec generada: `http://localhost:8080/v3/api-docs`.
- En el mismo selector están los contratos de `openapi/` (ms-transfers v3 y v1, core bancario, antifraude y directorio de alias), servidos en `/openapi/*`, para comparar el código contra el contrato.

## Autenticación (taller)

- **API Gateway** exige `x-api-key` (REST API con usage plan). La key la valida API Gateway; el micro no la ve.
- El cliente se identifica con el header **`X-Customer-Id`** (p. ej. `CUST-000123`). El micro lo usa para validar que la cuenta sea suya (403 `ACCOUNT_NOT_OWNED`).
- No hay JWT: se quitó para simplificar el taller.

## Variables de entorno

| Variable | Descripción | Default |
|----------|-------------|---------|
| `SERVICE_NAME` | Nombre del servicio | `ms-transfers` |
| `SERVER_PORT` | Puerto HTTP | `8080` |
| `AWS_REGION` | Región de AWS | `us-east-1` |
| `DYNAMODB_ENABLED` / `DYNAMODB_ENDPOINT` | Activa DynamoDB; endpoint solo para local | `false` / vacío |
| `DYNAMODB_TABLE_TRANSFERS` / `DYNAMODB_TABLE_IDEMPOTENCY` | Nombres de tablas | `transfers` / `idempotency` |
| `DYNAMODB_TABLE_RECIPIENT_TOKENS` | Tabla de tokens de destinatario (HU-03) | `recipient-tokens` |
| `SNS_ENABLED` / `SNS_TOPIC_ARN` | Publicación de eventos | `false` / vacío |
| `CORE_BASE_URL` / `FRAUD_BASE_URL` | Dependencias externas | `http://localhost:9090` / `http://localhost:9091` |
| `DIRECTORY_BASE_URL` / `DIRECTORY_TIMEOUT` | Directorio de alias (HU-03) | `http://localhost:9090` / `2s` |
| `CORE_TIMEOUT` / `FRAUD_TIMEOUT` | Timeout por llamada | `2s` / `1s` |
| `TRANSFER_LIMIT_PER_TX` / `TRANSFER_LIMIT_DAILY` | RN-04 y RN-05 | `10000.00` / `30000.00` |
| `TRANSFER_BUSINESS_ZONE` | Zona del día de negocio (RN-05) | `America/Lima` |
| `PHONE_TRANSFER_LIMIT_PER_TX` / `RECIPIENT_TOKEN_TTL` | RN-11 y RN-12 | `500.00` / `5m` |
| `SECRET_APIS_NAME` | Secreto de Secrets Manager con las llaves; el micro lo lee al arrancar | vacío (no lee nada) |
| `CORE_API_KEY` / `FRAUD_API_KEY` / `DIRECTORY_API_KEY` | **Secretos.** Salen del secreto `SECRET_APIS_NAME` | `local-dev-key` |

En dev, el pipeline escribe en `application.yml` los valores de la library antes de construir la imagen (ver [Valores de la library en la imagen](#valores-de-la-library-en-la-imagen)). Una variable de entorno en la tarea sigue teniendo prioridad.

Ningún valor sensible va en el repo. Ver `.env.example`.

## Pipeline y library

El pipeline está en `deployments/azure-pipelines.yml` (al registrarlo en Azure Pipelines, usar esa ruta).

### Library `pragma-ms-java-pragma-transfer`

| Variable | Secreta | Ejemplo | Para qué |
| --- | --- | --- | --- |
| `AWS_SERVICE_CONNECTION` | No | `aws-service-connection-dev` | Service connection de AWS que usan las tareas |
| `AWS_DEPLOY_ROLE` | No | `arn:aws:iam::123456789012:role/pragma-workshop-peru-devops-role-app` | Rol que asume el pipeline del micro (ECR, secreto y ECS) |
| `AWS_REGION` | No | `us-east-1` | Región de todo el despliegue |
| `ECR_REPOSITORY` | No | `pragma-transfers-dev-ecr-ms-transfers` | Repositorio ECR del micro (preexistente, HU-00) |
| `ECR_MOCKS_REPOSITORY` | No | `pragma-transfers-dev-ecr-mocks` | Repositorio ECR de la imagen de mocks (preexistente, HU-00) |
| `ECS_CLUSTER` | No | output de Terraform | Cluster donde se reinician las tareas |
| `ECS_SERVICE` | No | output de Terraform | Servicio del micro que se reinicia |
| `ECS_MOCKS_SERVICE` | No | output de Terraform | Servicio de mocks que se reinicia |
| `SECRET_APIS_NAME` | No | output de Terraform | Secreto con las llaves: el pipeline lo escribe y el micro lo lee al arrancar |
| `DYNAMODB_ENABLED` | No | `true` | Usa DynamoDB real (en `false`, repositorios en memoria) |
| `DYNAMODB_TABLE_TRANSFERS` | No | nombre real de la tabla | Tabla de transferencias |
| `DYNAMODB_TABLE_IDEMPOTENCY` | No | nombre real de la tabla | Tabla de idempotencia |
| `DYNAMODB_TABLE_RECIPIENT_TOKENS` | No | nombre real de la tabla | Tabla de tokens de destinatario (HU-03) |
| `SNS_ENABLED` | No | `true` | Publica los eventos en SNS |
| `SNS_TOPIC_ARN` | No | `arn:aws:sns:us-east-1:123456789012:pragma-transfers-dev-sns-transfers-events` | Tópico de eventos |
| `CORE_BASE_URL` | No | `http://mocks:8080` | Core bancario (mocks por Service Connect) |
| `FRAUD_BASE_URL` | No | `http://mocks:8080` | Antifraude (mocks) |
| `DIRECTORY_BASE_URL` | No | `http://mocks:8080` | Directorio de alias (mocks, HU-03) |
| `CORE_API_KEY` | **Sí** | — | Llave del core bancario (mock) |
| `FRAUD_API_KEY` | **Sí** | — | Llave de antifraude (mock) |
| `DIRECTORY_API_KEY` | **Sí** | — | Llave del directorio de alias; se agrega en la HU-03 |

`ECS_CLUSTER`, `ECS_SERVICE`, `ECS_MOCKS_SERVICE` y `SECRET_APIS_NAME` se copian a la library después del primer `apply` de la HU-02. La cuenta de AWS no va en la library: sale del rol asumido.

### Valores de la library en la imagen

Antes del `docker build`, el paso *Library values into application.yml* corre `deployments/scripts/render-config.py`: cambia el valor por defecto de cada `${VARIABLE:default}` por el de la library. Solo toca `AWS_REGION`, `SECRET_APIS_NAME`, `DYNAMODB_*`, `SNS_*` y las `*_BASE_URL`; si una no está en la library, queda el valor local. Las llaves de las APIs nunca entran a la imagen.

### Acceso a AWS

Cada tarea `AWSShellScript@1` entra con la service connection `$(AWS_SERVICE_CONNECTION)` y asume `$(AWS_DEPLOY_ROLE)` con un perfil del AWS CLI (`role_arn` + `credential_source`). El script no maneja llaves: el CLI hace el `assume-role`. Ni la service connection ni el rol están escritos en el YAML.

### Cómo se despliega

Detalle completo, rollback y trade-offs: [docs/deployment-strategy.md](docs/deployment-strategy.md).

**Modo taller:** Quality y Security no se borraron; están detrás del parámetro `runQualityGates` (por defecto `false`) para que el despliegue sea rápido. Para correrlos: *Run pipeline → marcar "Correr Quality y Security"*.

Las task definitions del micro y de mocks (de Terraform) apuntan siempre a la imagen `:latest`. Desplegar es subir las imágenes nuevas a ECR y reiniciar las tareas para que las descarguen; el pipeline no registra revisiones de las task definitions.

| Stage | Cuándo | Qué hace |
| --- | --- | --- |
| Quality | **Desactivado** (`runQualityGates`) | `./gradlew clean build`: pruebas, ArchUnit y cobertura del 90 % |
| Security | **Desactivado** (`runQualityGates`) | Secret scan con gitleaks |
| Build | Push a `main` | `docker build` del micro y de `mocks/` (WireMock con los stubs); push a sus ECR con los tags `latest` y `$(Build.BuildId)` |
| Secrets DEV | Push a `main`, environment `dev` | Escribe las llaves de la library en el secreto `$(SECRET_APIS_NAME)` |
| Restart ECS DEV | Push a `main`, environment `dev` | `update-service --force-new-deployment` de mocks y del micro, y espera a que los dos queden estables |

### Orden la primera vez

Los dos ECR y el bucket del state ya existen (HU-00).

1. Correr el pipeline de `pragma-iac-tf-pragma-transfer`: crea el secreto, el cluster y los servicios del micro y de mocks (sus tareas fallan hasta que existan las imágenes `:latest`; es esperado).
2. Copiar `ECS_CLUSTER`, `ECS_SERVICE`, `ECS_MOCKS_SERVICE` y `SECRET_APIS_NAME` a esta library.
3. Correr este pipeline en `main`: sube las dos imágenes `:latest`, escribe los secretos y reinicia las tareas. Desde aquí cada merge a `main` despliega solo.

### Cómo consume la app los secretos

1. *Secrets DEV* escribe en `$(SECRET_APIS_NAME)` un JSON con `CORE_API_KEY`, `FRAUD_API_KEY` y `DIRECTORY_API_KEY` de la library.
2. Al arrancar, `ApiKeysSecretEnvironmentPostProcessor` lee ese secreto con el task role y expone cada llave como propiedad; Spring las resuelve con `${CORE_API_KEY:local-dev-key}`.
3. Si no hay `SECRET_APIS_NAME`, el secreto no existe o falta el permiso, el micro arranca con `local-dev-key` y deja una advertencia en el log. Los mocks no validan la llave.

El task role necesita `secretsmanager:GetSecretValue` sobre el secreto y `kms:Decrypt` sobre su llave KMS. La task definition no tiene que inyectar nada.

## Notas del artefacto

- No usa `@RestController`, JPA, `RestTemplate`, `RestClient` ni Tomcat (reglas del stack WebFlux).
- No se permiten llamadas `.block()` en producción (BlockHound lo verifica en tests).
- DynamoDB y SNS se activan por bandera; el servicio arranca en local sin AWS (tablas y eventos en memoria).

## Limitaciones conocidas (HU-01)

- Sin Docker no se prueba contra DynamoDB ni SNS reales: los adaptadores se prueban con el SDK simulado (Mockito) y `AwsWiringTest` verifica que el contexto arma las tablas (con sus GSI) y el cliente SNS cuando las banderas están en `true`.
- El historial lee todas las transferencias de la cuenta en el GSI y pagina en memoria (DynamoDB no tiene offset). Suficiente para el taller; con volumen real convendría paginar por cursor (`LastEvaluatedKey`).
- Los eventos se publican después de guardar la transferencia, sin outbox: si SNS falla el evento se pierde (queda en el log).
- Los límites RN-04/RN-05 se aplican en la moneda de la operación (no hay tipo de cambio).

## Limitaciones conocidas (HU-03)

- Sin Docker no se prueba contra la tabla `recipient-tokens` real: el adaptador se prueba con el SDK simulado (se verifica la condición del uso único en el request) y la semántica completa con el adaptador en memoria, que replica la condición de forma atómica.
- La tabla `recipient-tokens` (con TTL en `expiresAt`) y la llave `DIRECTORY_API_KEY` en el secreto los crea la infraestructura (repo de Terraform y library); este repo solo los consume.
- RN-13 se valida por cuenta: destino igual a la origen o cuenta destino del mismo `customerId` en el core (una llamada `GET` extra de la cuenta destino antes del flujo v1).
- A antifraude se le envía como `target` la cuenta destino resuelta (el micro no guarda el celular completo).
- Si el cliente no es dueño de la cuenta origen, el 403 llega en el flujo v1, después de marcar el token como usado; un reintento con la misma llave lo vuelve a aceptar.
