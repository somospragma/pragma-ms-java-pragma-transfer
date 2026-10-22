# Catálogo de códigos de error

Códigos de negocio estables y versionados (ERR-BP-008). Los consumidores reaccionan por `code`, no por el texto. Base del scaffold: el código embebe el status HTTP (`TRF-<status>-<seq>`).

| Código | HTTP | Cuándo ocurre |
|--------|------|---------------|
| `TRF-400-001` | 400 | La petición no pasó la validación de entrada |
| `TRF-503-001` | 503 | Una dependencia (core, antifraude) no está disponible |
| `TRF-500-001` | 500 | Error técnico inesperado (no expone detalle interno) |

> **HU-01:** el contrato `openapi/ms-transfers-v1.yaml` define los códigos de negocio por nombre (`AMOUNT_INVALID`, `INSUFFICIENT_FUNDS`, `IDEMPOTENCY_KEY_REUSED`, ...). Al implementar las reglas RN-01 a RN-08 se alinea este catálogo y el mapeo a HTTP de `GlobalErrorWebExceptionHandler` con el contrato.

## Formato de respuesta (RFC 9457 / 7807 + extensiones)

```json
{
  "type": "https://errors.pragma.co/TRF-400-001",
  "title": "Validation error",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/transfers",
  "code": "TRF-400-001",
  "traceId": "b1e2...",
  "metadata": { "amount": "must be greater than 0" }
}
```

- **Errores de negocio** → 4xx, con `code` y `metadata` de contexto.
- **Errores técnicos** → 5xx, mensaje genérico `Unexpected error`, sin stack trace ni detalles internos.
