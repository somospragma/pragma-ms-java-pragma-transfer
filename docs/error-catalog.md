# Catálogo de códigos de error

Códigos de negocio estables y versionados (ERR-BP-008), iguales al enum `ErrorCode` de `openapi/ms-transfers-v1.yaml`. Los consumidores reaccionan por `code`, no por el texto. En el código viven en `ErrorCatalog` (dominio) junto con su status HTTP; `GlobalErrorWebExceptionHandler` los traduce a la respuesta.

| Código | HTTP | Cuándo ocurre |
|--------|------|---------------|
| `VALIDATION_ERROR` | 400 | La petición no cumple el contrato: campo faltante o con formato inválido, campo desconocido, `Idempotency-Key` ausente o no UUID, parámetros de paginación/filtros inválidos |
| `AMOUNT_INVALID` | 400 | RN-03: el monto no es mayor a 0 o tiene más de 2 decimales |
| `UNAUTHORIZED` | 401 | Falta el header `X-Customer-Id` (la API key la valida API Gateway antes) |
| `ACCOUNT_NOT_OWNED` | 403 | La cuenta origen (o la del historial) no es del cliente, o la transferencia consultada es de otro cliente |
| `TRANSFER_NOT_FOUND` | 404 | La transferencia consultada no existe |
| `IDEMPOTENCY_KEY_REUSED` | 409 | RN-08: el `Idempotency-Key` ya se usó con un cuerpo distinto |
| `ACCOUNT_INVALID` | 422 | RN-01: cuenta destino inexistente, cuenta origen o destino no activa, origen igual a destino, o el core rechaza el débito por cuenta bloqueada |
| `CURRENCY_MISMATCH` | 422 | RN-02: las cuentas o el monto no están en la misma moneda |
| `LIMIT_PER_TX_EXCEEDED` | 422 | RN-04: el monto supera el máximo por operación |
| `DAILY_LIMIT_EXCEEDED` | 422 | RN-05: el acumulado diario del cliente superaría el máximo |
| `INSUFFICIENT_FUNDS` | 422 | RN-06: saldo disponible insuficiente |
| `FRAUD_REJECTED` | 422 | RN-07: score de antifraude 80 o más |
| `DEPENDENCY_UNAVAILABLE` | 503 | El core no responde (timeout, 5xx o circuito abierto). Incluye `Retry-After`; se puede reintentar con el mismo `Idempotency-Key` |
| `UNEXPECTED_ERROR` | 500 | Error técnico inesperado (no expone detalle interno). Fuera del contrato |
| `ROUTE_NOT_FOUND` | 404 | Ruta inexistente del servicio. Fuera del contrato |

`failureCode` de una transferencia `REJECTED` es el código de la regla incumplida; en una `COMPENSATED` es `CREDIT_FAILED` (el crédito falló y se reversó el débito). El antifraude caído o lento **no** es un error: la transferencia queda `PENDING_REVIEW`.

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
