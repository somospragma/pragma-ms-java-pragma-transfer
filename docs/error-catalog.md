# Catálogo de códigos de error

Códigos de negocio estables y versionados (ERR-BP-008), iguales al enum `ErrorCode` de `openapi/ms-transfers-v3.yaml` (que incluye los de la v1). Los consumidores reaccionan por `code`, no por el texto. En el código viven en `ErrorCatalog` (dominio) junto con su status HTTP; `GlobalErrorWebExceptionHandler` los traduce a la respuesta.

| Código | HTTP | Cuándo ocurre |
|--------|------|---------------|
| `VALIDATION_ERROR` | 400 | La petición no cumple el contrato: campo faltante o con formato inválido, campo desconocido, `Idempotency-Key` ausente o no UUID, parámetros de paginación/filtros inválidos |
| `AMOUNT_INVALID` | 400 | RN-03: el monto no es mayor a 0 o tiene más de 2 decimales |
| `PHONE_INVALID` | 400 | RN-09 (HU-03): el celular falta o no cumple `^\+519\d{8}$` (sin `+51`, no empieza en 9 o largo distinto). `errors[0].field = phone` |
| `UNAUTHORIZED` | 401 | Falta el header `X-Customer-Id` (la API key la valida API Gateway antes) |
| `ACCOUNT_NOT_OWNED` | 403 | La cuenta origen (o la del historial) no es del cliente, o la transferencia consultada es de otro cliente |
| `TRANSFER_NOT_FOUND` | 404 | La transferencia consultada no existe |
| `RECIPIENT_NOT_FOUND` | 404 | RN-10 (HU-03): el celular no está afiliado al directorio de alias (o el alias PragBank está suspendido) |
| `IDEMPOTENCY_KEY_REUSED` | 409 | RN-08: el `Idempotency-Key` ya se usó con un cuerpo distinto |
| `ACCOUNT_INVALID` | 422 | RN-01: cuenta destino inexistente, cuenta origen o destino no activa, origen igual a destino, o el core rechaza el débito por cuenta bloqueada |
| `CURRENCY_MISMATCH` | 422 | RN-02: las cuentas o el monto no están en la misma moneda |
| `LIMIT_PER_TX_EXCEEDED` | 422 | RN-04: el monto supera el máximo por operación (S/ 10,000.00). En pago a celular, RN-11: más de S/ 500.00 o moneda distinta de PEN |
| `DAILY_LIMIT_EXCEEDED` | 422 | RN-05: el acumulado diario del cliente superaría el máximo |
| `INSUFFICIENT_FUNDS` | 422 | RN-06: saldo disponible insuficiente |
| `FRAUD_REJECTED` | 422 | RN-07: score de antifraude 80 o más |
| `RECIPIENT_NOT_SUPPORTED` | 422 | RN-10 (HU-03): el celular es de otro banco; por ahora solo se paga a clientes PragBank |
| `RECIPIENT_TOKEN_EXPIRED` | 422 | RN-12 (HU-03): el `recipientToken` no existe, venció (más de 5 min, aunque siga en la tabla), ya se usó en otro pago o es de otro cliente. No se debita nada |
| `SELF_TRANSFER` | 422 | RN-13 (HU-03): el celular destino es del mismo cliente que paga |
| `DEPENDENCY_UNAVAILABLE` | 503 | El core o el directorio de alias no responden (timeout, 5xx o circuito abierto). Incluye `Retry-After`; se puede reintentar con el mismo `Idempotency-Key` |
| `UNEXPECTED_ERROR` | 500 | Error técnico inesperado (no expone detalle interno). Fuera del contrato |
| `ROUTE_NOT_FOUND` | 404 | Ruta inexistente del servicio. Fuera del contrato |

`failureCode` de una transferencia `REJECTED` es el código de la regla incumplida; en una `COMPENSATED` es `CREDIT_FAILED` (el crédito falló y se reversó el débito). El antifraude caído o lento **no** es un error: la transferencia queda `PENDING_REVIEW`.

En pago a celular, `RECIPIENT_TOKEN_EXPIRED`, `SELF_TRANSFER` y el `LIMIT_PER_TX_EXCEEDED` de RN-11 se responden antes de crear la transferencia: no se guarda una `REJECTED`, no se publica evento y el token no se consume. Los demás rechazos (RN-04 a RN-07) siguen el flujo de la v1.

## Formato de respuesta (RFC 9457 / 7807 + extensiones del contrato)

```json
{
  "type": "https://api.pragbank.pe/errors/amount-invalid",
  "title": "Monto inválido",
  "status": 400,
  "code": "AMOUNT_INVALID",
  "detail": "El monto debe ser mayor a 0 y tener máximo 2 decimales",
  "instance": "/api/v1/transfers",
  "correlationId": "c0ffee00-1234-4abc-9def-000000000001",
  "errors": [{ "field": "amount.value", "message": "debe ser mayor a 0" }]
}
```

- **Errores de negocio** → 4xx con `code`, y `errors` cuando hay detalle por campo.
- **Errores técnicos** → 5xx con mensaje genérico, sin stack trace ni detalles internos.
