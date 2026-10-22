# Glosario de dominio (lenguaje ubicuo)

Vocabulario compartido entre negocio y código (NOM-BP-008). Los nombres del código reflejan este glosario.

| Término | Definición |
|---------|------------|
| Transfer | Movimiento de dinero entre dos cuentas PragBank. |
| Canal (channel) | `ACCOUNT` (cuenta a cuenta, HU-01) o `PHONE` (pago a celular, HU-03). Los dos usan el mismo flujo de débito, crédito y compensación. |
| Pago a celular | Transferencia (`channel = PHONE`) cuyo destino se identifica por el celular del titular; la cuenta destino la resuelve el directorio y nunca se muestra al cliente. |
| Directorio de alias | Servicio externo que traduce un celular a su banco y, si es PragBank, a la cuenta y el titular (`openapi/alias-directory.yaml`). |
| Alias | Celular registrado en el directorio. Puede ser `PRAGBANK` u `OTHER` (otro banco). |
| recipientToken | Token opaco (`rt_…`) que devuelve la resolución del celular. Representa al destinatario sin exponer su cuenta; es de un solo uso, del cliente que lo pidió y vence a los 5 minutos (RN-12). |
| Nombre enmascarado | Primeras 3 letras de cada palabra del titular + `***` (`Mar*** Qui***`), para que el cliente confirme a quién paga sin ver el nombre completo. |
| Celular enmascarado | `+51 9** *** 321`: el `targetPhoneMasked` de un pago a celular. |
| Uso único | Escritura condicional sobre `usedAt` en `recipient-tokens`: solo un pago puede usar el token (el reintento con la misma llave es el mismo pago). |
| Pago a uno mismo (SELF_TRANSFER) | Pago a un celular cuya cuenta es del mismo cliente que paga (RN-13). |
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
| PENDING_REVIEW | Estado final cuando antifraude da 60-79 o no responde: no se mueve dinero (aplica también a pago a celular). |
| Gateway | Puerto de salida del dominio hacia un sistema externo (nombre de dominio, nunca del sistema). |
| Use Case | Operación de negocio orquestada sin depender del framework. |
