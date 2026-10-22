# Tareas · HU-03 Pago a celular

Spec: [spec.md](spec.md) · Plan: [plan.md](plan.md)

Cada tarea se cierra con su prueba en verde. Verificación final: `./gradlew clean check --no-daemon` (pruebas, ArchUnit y gate de cobertura de línea del 90 %).

- [x] **T01 · Contrato v3 y documentación.** Copiar `ms-transfers-v3.yaml` a `openapi/` (la v1 queda igual) y agregarlo a Swagger UI como "ms-transfers v3 (contrato)"; la spec generada desde el código sigue como principal. Las dos rutas nuevas llevan `@RouterOperation` (headers, query `phone`, body, respuestas y ProblemDetail).
  Prueba: `OpenApiDocsTest` (`documentsPhoneTransferRoutes` y `/openapi/ms-transfers-v3.yaml`).
- [x] **T02 · Modelo de dominio.** `PhoneNumber` (RN-09 y enmascarado), `RecipientAlias` (RN-10, nombre enmascarado), `RecipientToken` (RN-12), `PhoneTransferPolicy` (RN-11), `PhoneTransferCommand` (huella), `TransferChannel.PHONE`, `Transfer.targetPhoneMasked` y códigos nuevos en `ErrorCatalog`.
  Prueba: `PhoneModelTest`.
- [x] **T03 · Puertos.** `IAliasDirectoryGateway` e `IRecipientTokenGateway` (`save`, `findById`, `redeem`).
  Prueba: `HexagonalArchitectureTest` (prefijo `I`, interfaces, dominio sin Spring).
- [x] **T04 · Resolver destinatario.** `ResolvePhoneRecipientUseCase`: RN-09 → directorio → RN-10 → token de 5 min.
  Prueba: `ResolvePhoneRecipientUseCaseTest`.
- [x] **T05 · Reutilizar el flujo v1.** Extraer `CreateTransferUseCase.execute(llave, huella, prepare)`; `execute(TransferCommand)` delega sin cambiar su comportamiento.
  Prueba: `CreateTransferUseCaseTest` y `TransfersAcceptanceTest` (HU-01) siguen en verde.
- [x] **T06 · Pagar a celular.** `CreatePhoneTransferUseCase`: RN-12 → RN-13 → RN-11 → uso único → flujo v1 con `channel = PHONE`; reintento reentrante por `usedByTransferId`.
  Prueba: `CreatePhoneTransferUseCaseTest`.
- [x] **T07 · Directorio de alias.** `AliasDirectoryAdapter` + `directoryWebClient` (`DIRECTORY_BASE_URL`, `DIRECTORY_API_KEY`) con timeout, circuit breaker y retry `directory`.
  Prueba: `AliasDirectoryAdapterTest` (200 PragBank/otro banco, 404, 5xx con reintentos, timeout, `+` literal y `X-Correlation-Id`).
- [x] **T08 · Tabla `recipient-tokens`.** `RecipientTokenItem`, `DynamoRecipientTokenAdapter` (actualización condicional sobre `usedAt` que también compara `expiresAt`), wiring por `DYNAMODB_TABLE_RECIPIENT_TOKENS`; `InMemoryRecipientTokenAdapter`; `targetPhoneMasked` en `transfers`.
  Prueba: `DynamoRecipientTokenAdapterTest`, `InMemoryRecipientTokenAdapterTest`, `AwsWiringTest`.
- [x] **T09 · Entry-point.** `PhoneTransferRouter`, `PhoneTransferHandler`, DTOs; `TransferResponse` con `targetPhoneMasked` y sin `targetAccountId` en PHONE.
  Prueba: `PhoneTransferHandlerTest` y `PhoneTransfersAcceptanceTest`.
- [x] **T10 · Configuración.** `transfers.phone.*`, `directory.*`, `dynamodb.table.recipient-tokens`, resilience4j `directory`, `.env.example`.
  Prueba: el contexto arranca en todas las pruebas `@SpringBootTest` (propiedades validadas al inicio).
- [x] **T11 · Aceptación HU-03** (`PhoneTransfersAcceptanceTest`, WireMock embebido con `mocks/mappings`):
  - [x] Celular PragBank → 200 con `maskedName` y token, sin cuenta (`celularPragbankDevuelveNombreEnmascaradoYTokenSinCuenta`).
  - [x] Inválido → 400 `PHONE_INVALID` (`celularInvalidoResponde400PhoneInvalid`).
  - [x] No afiliado → 404 `RECIPIENT_NOT_FOUND` (`celularNoAfiliadoResponde404`).
  - [x] Otro banco → 422 `RECIPIENT_NOT_SUPPORTED` (`celularDeOtroBancoResponde422`).
  - [x] Pago exitoso → 201 `COMPLETED`, `PHONE`, `targetPhoneMasked` (`pagoExitosoQuedaCompletedConCanalPhoneYCelularEnmascarado`).
  - [x] Token vencido → 422 sin débito (`tokenVencidoResponde422SinDebito`).
  - [x] Token usado → 422 sin segundo débito (`tokenYaUsadoResponde422SinSegundoDebito`).
  - [x] Token vencido aún en la tabla → rechazado por `expiresAt` (`tokenVencidoQueSigueEnLaTablaSeRechazaPorExpiresAt`).
  - [x] Propio celular → 422 `SELF_TRANSFER` (`pagoAMiPropioCelularResponde422SelfTransfer`).
  - [x] 500.01 o USD → 422 `LIMIT_PER_TX_EXCEEDED` (`montoMayorAQuinientosOEnDolaresResponde422LimitePorOperacion`).
  - [x] Reintento idempotente y 409 (`reintentoConLaMismaLlaveDevuelve200SinNuevoDebito`).
- [x] **T12 · Documentación.** README (sección HU-03, tabla, curl), `docs/error-catalog.md`, `docs/glossary.md`, `mocks/README.md`.
  Prueba: revisión manual.
- [x] **T13 · Gate de calidad.** `./gradlew clean check --no-daemon` en verde sin bajar umbrales.
