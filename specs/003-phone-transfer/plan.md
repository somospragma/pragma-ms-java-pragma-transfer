# Plan técnico · HU-03 Pago a celular

Spec: [spec.md](spec.md) · Tareas: [tasks.md](tasks.md)

## Enfoque

Se agrega el canal `PHONE` sin duplicar el flujo de dinero: el pago a celular resuelve el destinatario, valida RN-11 a RN-13 y entrega una transferencia ya armada al mismo `CreateTransferUseCase` de la v1 (idempotencia, reglas, antifraude, SAGA y eventos).

```
GET  /phone-transfers/recipients ──► ResolvePhoneRecipientUseCase ──► IAliasDirectoryGateway (WebClient)
                                                         └──────────► IRecipientTokenGateway (recipient-tokens)
POST /phone-transfers ──► CreatePhoneTransferUseCase
                            └─ CreateTransferUseCase.execute(llave, huella, prepare)
                                  ├─ RN-08 reserva de la llave (idempotency)
                                  ├─ prepare(transferId): RN-12 → RN-13 → RN-11 → uso único del token
                                  └─ flujo v1: 403, RN-04/05, RN-01/02, RN-07, RN-06, SAGA, eventos
```

## Dominio (`domain/model`, paquete `phone`)

| Clase | Responsabilidad |
| --- | --- |
| `PhoneNumber` | RN-09 (valida en el constructor → `PHONE_INVALID`) y enmascarado `+51 9** *** 321`. |
| `AliasBank`, `RecipientAlias` | Resultado del directorio; `isPayable()` (RN-10) y `maskedName()` (`Mar*** Qui***`). |
| `RecipientToken` | Token de destinatario; `isExpired(now)`, `isUsed()`, `isRedeemableBy(cliente, transferId, now)` (RN-12). |
| `PhoneTransferPolicy` | RN-11 (máximo y moneda) y vigencia del token, por configuración. |
| `PhoneTransferCommand` | Orden de pago y su huella (incluye el canal para no chocar con la v1). |

Cambios en lo existente: `TransferChannel.PHONE`; `Transfer.targetPhoneMasked`; nuevos códigos en `ErrorCatalog` (`PHONE_INVALID`, `RECIPIENT_NOT_FOUND`, `RECIPIENT_NOT_SUPPORTED`, `RECIPIENT_TOKEN_EXPIRED`, `SELF_TRANSFER`).

## Puertos (`domain/ports`)

- `IAliasDirectoryGateway.resolve(PhoneNumber)`: vacío si no está afiliado.
- `IRecipientTokenGateway`: `save`, `findById` (lectura consistente) y `redeem(token, cliente, transferId, now)` → `true` si la escritura condicional pasó.

## Casos de uso (`domain/usecases`)

- `ResolvePhoneRecipientUseCase`: RN-09 → directorio → RN-10 → token `rt_` + 128 bits (`SecureRandom`, base64url) con `expiresAt = now + 5 min` truncado a segundos (precisión del TTL).
- `CreatePhoneTransferUseCase`: usa `CreateTransferUseCase.execute(llave, huella, prepare)`. `prepare` lee el token, aplica RN-12, valida que la cuenta origen sea del cliente (403), RN-13 (cuenta destino igual a la origen o del mismo cliente según el core), RN-11 y recién entonces consume el token. Los rechazos previos no consumen el token ni guardan transferencia.
- `CreateTransferUseCase`: se extrae `execute(llave, huella, prepare)` del `execute(TransferCommand)` existente (que ahora delega). El reintento de una reserva sin transferencia guardada vuelve a llamar a `prepare` con el mismo `transferId`; por eso el token acepta a la transferencia que ya lo usó.

## Adaptadores

| Módulo | Cambio |
| --- | --- |
| `external-api` | `AliasDirectoryAdapter` + `AliasDto`; WebClient `directoryWebClient` (`directory.base-url`, `X-Api-Key` de `directory.api-key`); `OutboundCalls.read` (timeout 2 s, circuit breaker y retry `directory`). El celular va literal en el path (`+`, no `%2B`). |
| `dynamodb-persistence` | Tabla `recipient-tokens` (`RecipientTokenItem`, `DynamoRecipientTokenAdapter`, `RecipientTokenConverter`); uso único con `UpdateItem` condicional e `IgnoreNullsMode.SCALAR_ONLY`. `InMemoryRecipientTokenAdapter` para local y pruebas. `TransferItem.targetPhoneMasked`. |
| `reactive-web` | `PhoneTransferRouter` (con `@RouterOperations` para la spec generada en `/v3/api-docs`), `PhoneTransferHandler`, `CreatePhoneTransferRequest`, `RecipientResolutionResponse`; `TransferResponse` devuelve `targetPhoneMasked` en PHONE y oculta `targetAccountId`. |
| `app-service` | `transfers.phone.*` en `TransfersProperties` y bean `PhoneTransferPolicy`; `application.yml` (`directory`, `dynamodb.table.recipient-tokens`, resilience4j `directory`); Swagger UI agrega el contrato v3 (la spec generada desde el código sigue como principal); `OpenApiConfig` versión 1.1.0. |

### Tabla `recipient-tokens`

PK `recipientToken` (S). Atributos: `customerId`, `targetAccountId`, `targetPhoneMasked`, `maskedName`, `createdAt`, `expiresAt` (N, epoch s, **TTL**), `usedAt`, `usedByTransferId`. Sin índices. Condición del uso único:

```
attribute_exists(recipientToken) AND customerId = :customer AND
((attribute_not_exists(usedAt) AND expiresAt > :now) OR usedByTransferId = :transfer)
```

## Configuración

| Propiedad | Variable | Default |
| --- | --- | --- |
| `directory.base-url` | `DIRECTORY_BASE_URL` | `http://localhost:9090` |
| `directory.api-key` | `DIRECTORY_API_KEY` (secreto) | vacío |
| `directory.timeout` | `DIRECTORY_TIMEOUT` | `2s` |
| `dynamodb.table.recipient-tokens` | `DYNAMODB_TABLE_RECIPIENT_TOKENS` | `recipient-tokens` |
| `transfers.phone.per-transaction` | `PHONE_TRANSFER_LIMIT_PER_TX` | `500.00` |
| `transfers.phone.currency` | — | `PEN` |
| `transfers.phone.token-ttl` | `RECIPIENT_TOKEN_TTL` | `5m` |

## Estrategia de pruebas

Sin Docker (igual que la HU-01):

- Unitarias de dominio (`PhoneModelTest`) y de casos de uso con puertos simulados (`ResolvePhoneRecipientUseCaseTest`, `CreatePhoneTransferUseCaseTest`, este último sobre el `CreateTransferUseCase` real).
- Adaptadores: `AliasDirectoryAdapterTest` (WireMock embebido), `DynamoRecipientTokenAdapterTest` (enhanced client simulado), `InMemoryRecipientTokenAdapterTest`.
- Aceptación: `PhoneTransfersAcceptanceTest` levanta el servicio completo con WireMock embebido cargando `mocks/mappings` (incluye `directory-*.json`) y un reloj que se puede adelantar para el vencimiento.
- Regresión: `TransfersAcceptanceTest` y el resto de la HU-01 sin cambios; `./gradlew clean check` con el gate de 90 % y ArchUnit.

## Decisiones

1. **Reutilizar el flujo v1 por composición** (`prepare`) en vez de duplicar la SAGA: un solo lugar para idempotencia, límites, antifraude, compensación y eventos.
2. **Idempotencia antes que el token**: el reintento de un pago completado responde 200 aunque el token ya esté usado.
3. **Token reentrante por `usedByTransferId`**: si un 503 corta el flujo después de consumir el token, el reintento con la misma llave lo vuelve a aceptar (aunque hayan pasado los 5 minutos).
4. **Rechazos de RN-11 a RN-13 sin persistir**: ocurren antes de crear la transferencia; no consumen el token (el cliente puede corregir el monto) ni publican evento.
5. **Datos mínimos**: no se guarda el celular completo ni el nombre del titular; la cuenta destino solo vive en `recipient-tokens` y `transfers`.
6. **Token de otro cliente o inexistente → `RECIPIENT_TOKEN_EXPIRED`**: no se revela si existe.
