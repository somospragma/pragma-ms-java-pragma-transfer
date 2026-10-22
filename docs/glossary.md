# Glosario de dominio (lenguaje ubicuo)

Vocabulario compartido entre negocio y código (NOM-BP-008). Los nombres del código reflejan este glosario.

| Término | Definición |
|---------|------------|
| Transfer | Movimiento de dinero entre dos cuentas PragBank. |
| Transfer Status | `RECEIVED`, `DEBITED`, `COMPLETED`, `PENDING_REVIEW`, `REJECTED`, `COMPENSATED`. |
| Idempotency-Key | Llave que envía la app para que un reintento no genere un segundo cargo (RN-08). |
| Core bancario | Sistema dueño de cuentas y saldos. Débitos, créditos y reversas son idempotentes por `operationId`. |
| Score de fraude | Puntaje 0-100 de antifraude (RN-07): menor a 60 aprueba, 60-79 revisión, 80 o más rechaza. |
| Compensación | Reversa del débito cuando el crédito falla (SAGA de dos pasos). |
| Gateway | Puerto de salida del dominio hacia un sistema externo (nombre de dominio, nunca del sistema). |
| Use Case | Operación de negocio orquestada sin depender del framework. |
