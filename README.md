# pragma-ms-java-pragma-transfer · ms-transfers

Microservicio de transferencias entre cuentas de **PragBank Perú**. Base: archetype de Pragma `java-webflux-gradle`, con la misma estructura hexagonal y los mismos quality gates.

> **Estado: scaffold (HU-00).** Tiene la base transversal lista: errores RFC 7807, headers de seguridad, correlation id, health, clientes de DynamoDB/SNS/WebClient y quality gates. Las transferencias (endpoints, reglas RN-01 a RN-08 de [docs/business-rules.md](docs/business-rules.md), SAGA, eventos) se construyen en la **HU-01** con el contrato `openapi/ms-transfers-v1.yaml`.

## Información general

- **Framework**: Spring Boot 4.x (WebFlux, reactivo, Netty)
- **Lenguaje**: Java 21
- **Build**: Gradle multi-módulo (Kotlin DSL) con version catalog
- **Arquitectura**: hexagonal; el dominio no depende de Spring ni del SDK de AWS (ArchUnit lo verifica)
- **Persistencia**: DynamoDB on-demand con el SDK v2 asíncrono (tablas `transfers` e `idempotency`)
- **HTTP saliente**: WebClient con timeouts, retry, circuit breaker y bulkhead (resilience4j)
- **Eventos**: SNS `transfers-events` (`openapi/transfers-events.asyncapi.yaml`)
- **Paquete base**: `co.com.pragma.transfers`
- **Cobertura**: gate de 90 % de línea (JaCoCo) enganchado a `check`

## Requisitos y arranque en local

| Herramienta | Versión | Verificar |
| --- | --- | --- |
| Git + llave SSH registrada en `dev.azure.com/your-ado-org` | — | `git clone git@ssh.dev.azure.com:v3/your-ado-org/workshop-peru/pragma-ms-java-pragma-transfer` |
| JDK | 21 (Temurin) | `java -version` |
| Docker Desktop (WireMock y Testcontainers) | 4.x encendido | `docker run --rm hello-world` |
| IDE | IntelliJ IDEA o VS Code con Java | — |
| curl o Bruno | — | `curl --version` |

```bash
# 1. Mocks del core bancario, antifraude y directorio (desde la raíz del repo)
docker run --rm -p 9090:8080 -v $PWD/mocks:/home/wiremock wiremock/wiremock:3.9.1

# 2. En otra terminal: compilar y probar
./gradlew build

# 3. Levantar el micro apuntando a los mocks
CORE_BASE_URL=http://localhost:9090 FRAUD_BASE_URL=http://localhost:9090 \
  ./gradlew :application:app-service:bootRun

# 4. Verificar
curl -s http://localhost:8080/actuator/health/liveness
curl -s http://localhost:9090/core/v1/accounts/191-0001234567
```

## Comandos

```bash
./gradlew build                             # compila, pruebas, ArchUnit y gate de cobertura
./gradlew jacocoRootReport                  # reporte de cobertura (build/reports/jacoco)
./gradlew :domain:usecases:pitest           # mutation testing del dominio
./gradlew :application:app-service:bootRun  # ejecutar el servicio
```

## Estructura

```
pragma-ms-java-pragma-transfer/
├── domain/
│   ├── model/         # Transfer, estados, excepciones (code/metadata), @Sensitive
│   ├── ports/         # Gateways: transferencias, idempotencia, core, antifraude, eventos
│   └── usecases/      # Casos de uso sin Spring (sufijo UseCase)
├── infrastructure/
│   ├── entry-points/reactive-web/     # Router + Handler + RFC 7807 + security headers + validación
│   ├── driven-adapters/
│   │   ├── dynamodb-persistence/      # Tablas transfers e idempotency  [dynamodb.enabled]
│   │   ├── external-api/              # WebClient de core bancario y antifraude
│   │   └── sns-publisher/             # Eventos de dominio a SNS  [sns.enabled]
│   └── helpers/       # CorrelationWebFilter, DataMasker, AesGcmCipher
├── application/app-service/  # MainApplication, config, application.yml, logback-spring.xml
├── openapi/           # Contratos: API propia, core bancario, antifraude y eventos
├── mocks/             # Stubs de WireMock del core y antifraude, y su Dockerfile para ECS
├── deployments/       # azure-pipelines.yml
└── docs/              # ADRs, catálogo de errores, glosario, runbook, threat model
```

## Documentación de la API (Swagger)

La especificación **se genera desde el código** con springdoc. Las rutas funcionales de WebFlux no se descubren solas: **cada ruta del router debe llevar su `@RouterOperation`** (headers, parámetros, cuerpo y respuestas) para aparecer en la spec.

- UI: `http://localhost:8080/swagger-ui.html` → definición *ms-transfers (generado desde el código)*. Botón **Authorize** para `x-api-key`.
- Spec generada: `http://localhost:8080/v3/api-docs`.
- En el mismo selector están los contratos de `openapi/` (ms-transfers v1, core bancario, antifraude y directorio de alias), servidos en `/openapi/*`, para comparar el código contra el contrato.

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
| `SNS_ENABLED` / `SNS_TOPIC_ARN` | Publicación de eventos | `false` / vacío |
| `CORE_BASE_URL` / `FRAUD_BASE_URL` | Dependencias externas | `http://localhost:9090` / `http://localhost:9091` |
| `SECRET_APIS_NAME` | Secreto de Secrets Manager con las llaves; el micro lo lee al arrancar | vacío (no lee nada) |
| `CORE_API_KEY` / `FRAUD_API_KEY` | **Secretos.** Salen del secreto `SECRET_APIS_NAME` | `local-dev-key` |

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
- DynamoDB y SNS se activan por bandera; el servicio arranca en local sin AWS.
