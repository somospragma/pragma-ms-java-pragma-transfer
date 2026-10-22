# Spec · HU-03 Pago a celular

| Campo | Valor |
| --- | --- |
| Historia | HU-03 · Pago a celular con SDD |
| Servicio | `ms-transfers` |
| Contrato | `openapi/ms-transfers-v3.yaml` (tag **Pago a celular**) |
| Dependencias | Directorio de alias (`openapi/alias-directory.yaml`), core bancario, antifraude, SNS `transfers-events` |
| Base | HU-01 (reglas RN-01 a RN-08, SAGA y eventos) |

## Historia

**Como** cliente de PragBank
**quiero** pagarle a otra persona usando solo su número de celular
**para** no tener que pedirle su número de cuenta.

## Alcance

- `GET /api/v1/phone-transfers/recipients?phone=…`: resuelve el celular y devuelve un `recipientToken` con el nombre enmascarado del titular.
- `POST /api/v1/phone-transfers`: ejecuta el pago con el `recipientToken`, reutilizando el flujo de la v1 con `channel = PHONE`.

Fuera de alcance: pagos a celulares de otros bancos (interoperabilidad), tipo de cambio, registro de alias.

## Reglas de negocio

| Regla | Descripción | Error |
| --- | --- | --- |
| RN-09 | El celular debe estar en formato E.164 peruano: `+51` seguido de 9 dígitos que empiezan en 9 (`^\+519\d{8}$`). | 400 `PHONE_INVALID` |
| RN-10 | El celular se resuelve en el directorio de alias. Si no está afiliado → no encontrado; si es de otro banco → no soportado. Solo se paga a clientes PragBank. | 404 `RECIPIENT_NOT_FOUND` / 422 `RECIPIENT_NOT_SUPPORTED` |
| RN-11 | El pago a celular admite solo soles (PEN) y como máximo S/ 500.00 por operación. | 422 `LIMIT_PER_TX_EXCEEDED` |
| RN-12 | El `recipientToken` es de un solo uso, pertenece al cliente que lo pidió y vence a los 5 minutos. La cuenta destino nunca se expone al cliente. | 422 `RECIPIENT_TOKEN_EXPIRED` |
| RN-13 | No se puede pagar a un celular del mismo cliente que paga. | 422 `SELF_TRANSFER` |

Además aplican de la v1: RN-03 (monto > 0 con máximo 2 decimales), RN-05 (acumulado diario), RN-06 (saldo), RN-07 (antifraude) y RN-08 (idempotencia por `Idempotency-Key`). El flujo reutilizado también conserva RN-01, RN-02 y RN-04, que no cambian el resultado de un pago válido a celular.

### Orden de evaluación

1. Resolver: RN-09 → RN-10 → emitir token (RN-12).
2. Pagar: forma (RN-03) → RN-08 → RN-12 → cuenta origen del cliente (403) → RN-13 → RN-11 → uso único del token → flujo v1 (RN-04, RN-05, RN-01, RN-02, RN-07, RN-06, SAGA).

## Criterios de aceptación

```gherkin
Característica: Pago a celular

  Antecedentes:
    Dado que soy el cliente "CUST-000123" dueño de la cuenta "191-0001234567" con S/ 2,500.00
    Y el directorio de alias tiene:
      | celular       | banco    | cuenta         | titular      |
      | +51987654321  | PRAGBANK | 191-0007654321 | Mario Quiroz |
      | +51999111222  | PRAGBANK | 191-0001234567 | María Quispe |
      | +51912345678  | OTHER    |                |              |

  Escenario: Resolver un celular PragBank
    Cuando consulto el destinatario del celular "+51987654321"
    Entonces la respuesta es 200
    Y el maskedName es "Mar*** Qui***"
    Y recibo un recipientToken que vence en 5 minutos
    Y la respuesta no contiene el número de cuenta ni el nombre completo

  Esquema del escenario: Celular que no se puede resolver
    Cuando consulto el destinatario del celular "<celular>"
    Entonces la respuesta es <status> con code "<code>"

    Ejemplos:
      | celular      | status | code                    |
      | 987654321    | 400    | PHONE_INVALID           |
      | 51987654321  | 400    | PHONE_INVALID           |
      | +51955555555 | 404    | RECIPIENT_NOT_FOUND     |
      | +51912345678 | 422    | RECIPIENT_NOT_SUPPORTED |

  Escenario: Pago exitoso a celular
    Dado que resolví el celular "+51987654321" y tengo su recipientToken
    Cuando pago S/ 45.50 en PEN con un Idempotency-Key nuevo
    Entonces la respuesta es 201
    Y el status es "COMPLETED" y el channel es "PHONE"
    Y el targetPhoneMasked es "+51 9** *** 321"
    Y la respuesta no contiene targetAccountId
    Y se publica el evento TransferCompleted con channel "PHONE"

  Escenario: Reintento idempotente del pago
    Dado que ya pagué con el Idempotency-Key "K"
    Cuando repito el mismo pago con el Idempotency-Key "K"
    Entonces la respuesta es 200 con el pago original
    Y no hay un segundo débito

  Escenario: Token vencido
    Dado que resolví el celular "+51987654321" hace más de 5 minutos
    Cuando pago S/ 45.50 con ese recipientToken
    Entonces la respuesta es 422 con code "RECIPIENT_TOKEN_EXPIRED"
    Y no se debita la cuenta origen

  Escenario: Token vencido que el TTL aún no borró
    Dado un recipientToken cuyo expiresAt ya pasó pero sigue en la tabla recipient-tokens
    Cuando pago con ese recipientToken
    Entonces la respuesta es 422 con code "RECIPIENT_TOKEN_EXPIRED"
    Y no se debita la cuenta origen

  Escenario: Token ya usado
    Dado que ya pagué con un recipientToken
    Cuando pago otra vez con el mismo recipientToken y otro Idempotency-Key
    Entonces la respuesta es 422 con code "RECIPIENT_TOKEN_EXPIRED"
    Y hay un solo débito

  Escenario: Pago a mi propio celular
    Dado que resolví el celular "+51999111222", que es de mi cuenta
    Cuando pago S/ 45.50 con ese recipientToken
    Entonces la respuesta es 422 con code "SELF_TRANSFER"
    Y no se debita la cuenta origen

  Esquema del escenario: Límite del pago a celular
    Dado que resolví el celular "+51987654321"
    Cuando pago <monto> en <moneda>
    Entonces la respuesta es 422 con code "LIMIT_PER_TX_EXCEEDED"
    Y no se debita la cuenta origen
    Y el recipientToken sigue disponible

    Ejemplos:
      | monto  | moneda |
      | 500.01 | PEN    |
      | 10.00  | USD    |
```

## Requisitos no funcionales

- La respuesta de resolución y la de pago nunca incluyen la cuenta destino ni el nombre completo; los logs no registran el celular completo.
- El micro valida `expiresAt` sin depender del borrado del TTL de DynamoDB.
- Uso único garantizado por escritura condicional (seguro ante pagos concurrentes con el mismo token).
- El directorio se llama con timeout, circuit breaker y retry; si no responde → 503 `DEPENDENCY_UNAVAILABLE` con `Retry-After`.
- Sin regresiones en la HU-01; gate de cobertura de línea del 90 % y reglas de ArchUnit.
