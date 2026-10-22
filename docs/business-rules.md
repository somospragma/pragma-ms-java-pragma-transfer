# PragBank · Reglas de negocio de ms-transfers

Catálogo de las reglas que implementa `ms-transfers`: RN-01 a RN-08 en la HU-01 (transferencias entre cuentas) y RN-09 a RN-13 en la HU-03 (pago a celular). Cada `code` es el del contrato (`openapi/ms-transfers-v1.yaml` y `ms-transfers-v3.yaml`) y viaja en el cuerpo RFC 9457 de la respuesta.

## HU-01 · Transferencias entre cuentas PragBank

| Regla | Qué valida | Si no se cumple |
| --- | --- | --- |
| **RN-01** | Las cuentas origen y destino existen en el core, están `ACTIVE` y son distintas | 422 `ACCOUNT_INVALID` |
| **RN-02** | Las dos cuentas y el monto están en la misma moneda | 422 `CURRENCY_MISMATCH` |
| **RN-03** | Monto mayor que 0, con máximo 2 decimales | 400 `AMOUNT_INVALID` |
| **RN-04** | Máximo S/ 10,000.00 por operación | 422 `LIMIT_PER_TX_EXCEEDED` |
| **RN-05** | Máximo S/ 30,000.00 acumulado por cliente y día de negocio (`America/Lima`). Suman las transferencias en `RECEIVED`, `DEBITED`, `COMPLETED` y `PENDING_REVIEW` | 422 `DAILY_LIMIT_EXCEEDED` |
| **RN-06** | Saldo disponible suficiente en la cuenta origen | 422 `INSUFFICIENT_FUNDS` |
| **RN-07** | Antifraude: score `< 60` aprueba; `60–79`, timeout (1 s) o caída deja la transferencia en revisión sin débito; `>= 80` rechaza | 201 `PENDING_REVIEW` · 422 `FRAUD_REJECTED` |
| **RN-08** | Idempotencia por `Idempotency-Key` (UUID, 24 h): misma llave y mismo cuerpo devuelve la transferencia original sin nuevo cargo; misma llave con otro cuerpo se rechaza | 200 · 409 `IDEMPOTENCY_KEY_REUSED` |

Además (no es una RN numerada, pero se valida en todas las rutas):

| Validación | Si no se cumple |
| --- | --- |
| Header `X-Customer-Id` presente | 401 `UNAUTHORIZED` |
| La cuenta origen (o la consultada) es del cliente según el core | 403 `ACCOUNT_NOT_OWNED` |
| Transferencia consultada existe | 404 `TRANSFER_NOT_FOUND` |
| Esquema del contrato (campos obligatorios, sin campos extra) | 400 `VALIDATION_ERROR` |
| Core no disponible | 503 `DEPENDENCY_UNAVAILABLE` con `Retry-After` |

### Orden de evaluación del `POST /api/v1/transfers`

1. Forma de la petición y RN-03 (400).
2. RN-08 idempotencia.
3. Cuenta origen del cliente (403; no se guarda nada).
4. RN-04 y RN-05.
5. RN-01 y RN-02.
6. RN-07 antifraude.
7. RN-06 saldo (va después de antifraude: `PENDING_REVIEW` no mueve dinero).
8. SAGA: débito → crédito. Si el crédito falla se reversa el débito y la transferencia queda `COMPENSATED` (201, `failureCode: CREDIT_FAILED`).

Los rechazos de los pasos 4 a 7 se guardan como `REJECTED` con su `failureCode` y publican `TransferRejected`; repetir la misma llave devuelve el mismo 422.

## HU-03 · Pago a un celular PragBank

Reusa RN-03 y RN-05 a RN-08 de la HU-01, más:

| Regla | Qué valida | Si no se cumple |
| --- | --- | --- |
| **RN-09** | Celular con formato `+51` + 9 dígitos que empiezan en 9 (`^\+519\d{8}$`) | 400 `PHONE_INVALID` |
| **RN-10** | El celular está afiliado en el directorio de alias y es de PragBank | 404 `RECIPIENT_NOT_FOUND` · 422 `RECIPIENT_NOT_SUPPORTED` |
| **RN-11** | Pago a celular de máximo S/ 500.00 y solo en `PEN` | 422 `LIMIT_PER_TX_EXCEEDED` |
| **RN-12** | El `recipientToken` existe, es del mismo cliente, está vigente (5 minutos) y no se ha usado (un solo uso) | 422 `RECIPIENT_TOKEN_EXPIRED` |
| **RN-13** | El destinatario no es el mismo cliente (cuenta destino igual a la origen o del mismo `customerId` en el core) | 422 `SELF_TRANSFER` |

### Orden de evaluación del `POST /api/v1/phone-transfers`

1. Forma de la petición y RN-03 (400).
2. RN-08 idempotencia.
3. RN-12 token.
4. Cuenta origen del cliente (403).
5. RN-13.
6. RN-11.
7. Uso único del token (escritura condicional).
8. Flujo de la HU-01 con `channel = PHONE`: RN-04, RN-05, RN-01, RN-02, RN-07, RN-06 y SAGA.

Los rechazos de los pasos 3 a 6 no consumen el token, no guardan transferencia ni publican evento.

## Datos de prueba (mocks)

| Dato | Para probar |
| --- | --- |
| Cuenta `191-0001234567` (cliente `CUST-000123`, S/ 2,500.00) | Origen fija |
| Cuenta `191-0007654321` | Destino |
| Cuenta `191-0009999999` | Crédito falla → `COMPENSATED` |
| Cuenta `191-8` + 9 dígitos (dueño `CUST-` + la cuenta, S/ 50,000.00) | Cliente nuevo por prueba (RN-05 en cero) |
| Cuenta `191-7` + 9 dígitos (S/ 100.00) | RN-06 |
| Monto `9500.00` | RN-07 → `PENDING_REVIEW` |
| Celular `+51987654321` | Pago exitoso |
| Celular `+51999111222` (de `CUST-000123`) | RN-13 |
| Celular `+51912345678` | RN-10 otro banco |
| Cualquier otro celular válido | RN-10 no afiliado |
