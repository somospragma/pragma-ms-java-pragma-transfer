# Threat model (resumen)

Modelado de amenazas de ms-transfers (SEG-BP-012).

## Activos
- Dinero de los clientes (débitos y créditos en el core).
- Datos de cuentas y movimientos.
- Llaves de las dependencias (`CORE_API_KEY`, `FRAUD_API_KEY`).

## Amenazas y controles (STRIDE resumido)

| Amenaza | Escenario de abuso | Control |
|---------|--------------------|---------|
| Spoofing | Peticiones sin autenticar | API key (`x-api-key`) validada por API Gateway con usage plan; el cliente se identifica con `X-Customer-Id`. En el taller no hay JWT: un cliente con la API key podría enviar otro `X-Customer-Id` (riesgo aceptado para dev) |
| Tampering | Reenvío de una transferencia para cobrar dos veces | `Idempotency-Key` obligatoria (RN-08) y `operationId` idempotente en el core |
| Repudiation | Falta de trazabilidad | Correlation id, logs estructurados y eventos en SNS |
| Information disclosure | Fuga de datos o secretos en logs o errores | Masking en logs; RFC 7807 sin internals; secretos solo por variables de entorno desde Secrets Manager |
| Denial of Service | Historial sin límite o payloads grandes | Paginación con tope y límites de payload |
| Elevation of privilege | Consultar o debitar una cuenta ajena (IDOR) | Verificar que la cuenta origen pertenece al `X-Customer-Id` (403 `ACCOUNT_NOT_OWNED`) |
| Fraude | Transferencias inusuales | Score de antifraude (RN-07) antes de debitar |
| Cascada de fallos | Core o antifraude caídos | Timeout, circuit breaker, bulkhead; antifraude en timeout deja `PENDING_REVIEW` |
