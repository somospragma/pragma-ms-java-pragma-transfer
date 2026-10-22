# Glosario de dominio (lenguaje ubicuo)

Vocabulario compartido entre negocio y código (NOM-BP-008). Los nombres del código reflejan este glosario.

| Término | Definición |
|---------|------------|
| Transfer | Movimiento de dinero entre dos cuentas PragBank. |
| Transfer Status | `RECEIVED`, `DEBITED`, `COMPLETED`, `PENDING_REVIEW`, `REJECTED`, `COMPENSATED`. |
| Idempotency-Key | Llave (UUID) que envía la app para que un reintento no genere un segundo cargo (RN-08). Vigencia 24 h; misma llave con otro cuerpo → 409. |
| Huella (fingerprint) | SHA-256 del cuerpo normalizado + cliente; distingue una repetición de un cuerpo distinto con la misma llave. |
| operationId | Llave de idempotencia de cada movimiento en el core: `TRF-{transferId}-DEBIT`, `-CREDIT`, `-REVERSAL`. |
| X-Customer-Id | Header que identifica al cliente en el taller (reemplaza al JWT). |
| Día de negocio | Fecha en `America/Lima` con la que se acumula el límite diario (RN-05). |
| failureCode | Código de la regla que rechazó la transferencia (`REJECTED`) o `CREDIT_FAILED` (`COMPENSATED`). |
| Core bancario | Sistema dueño de cuentas y saldos. Débitos, créditos y reversas son idempotentes por `operationId`. |
| Score de fraude | Puntaje 0-100 de antifraude (RN-07): menor a 60 aprueba, 60-79 revisión, 80 o más rechaza. |
| Compensación | Reversa del débito cuando el crédito falla (SAGA de dos pasos); la transferencia queda `COMPENSATED`. |
| PENDING_REVIEW | Estado final de la v1 cuando antifraude da 60-79 o no responde: no se mueve dinero. |
| Gateway | Puerto de salida del dominio hacia un sistema externo (nombre de dominio, nunca del sistema). |
| Use Case | Operación de negocio orquestada sin depender del framework. |
