# mocks · WireMock de core bancario, antifraude y directorio de alias

Stubs de las dependencias externas de `ms-transfers`, según `openapi/core-banking.yaml`, `openapi/fraud.yaml` y `openapi/alias-directory.yaml` (este último para la HU-03).
Las rutas no chocan (`/core/v1/...`, `/fraud/v1/...` y `/directory/v1/...`), así que un solo WireMock sirve las tres.

| Dato | Comportamiento |
| --- | --- |
| Cuenta `191-0001234567` | Origen activa en PEN con S/ 2,500.00 |
| Cuenta `191-0007654321` | Destino activa en PEN |
| Cuenta `191-0009999999` | Destino activa; el crédito responde 503 (escenario de compensación) |
| Cuenta `191-8` + 9 dígitos (p. ej. `191-8123456789`) | Activa en PEN con S/ 50,000.00; su dueño es `CUST-` + la cuenta (`CUST-191-8123456789`). Para pruebas con un cliente nuevo en cada corrida (límite diario en cero) |
| Cuenta `191-7` + 9 dígitos | Igual, pero con S/ 100.00 (saldo insuficiente) |
| Cualquier otra cuenta | 404 `ACCOUNT_NOT_FOUND` |
| Monto `9500.00` | Antifraude responde score 67 (`PENDING_REVIEW`) |
| Cualquier otro monto | Antifraude responde score 12 |
| Celular `+51987654321` | Directorio: cliente PragBank Mario Quiroz, cuenta `191-0007654321` (HU-03) |
| Celular `+51999111222` | Directorio: es de la cuenta origen `191-0001234567`, para probar `SELF_TRANSFER` (HU-03) |
| Celular `+51912345678` | Directorio: otro banco, para probar `RECIPIENT_NOT_SUPPORTED` (HU-03) |
| Cualquier otro celular | Directorio: 404, para probar `RECIPIENT_NOT_FOUND` (HU-03) |

Los débitos, créditos y reversas devuelven el `operationId` recibido. Si un escenario necesita otro dato, se agrega un JSON en `mappings/`.

## Correr en local

```bash
docker run --rm -p 9090:8080 -v "$PWD/mocks:/home/wiremock" wiremock/wiremock:3.9.1
```

Con un solo contenedor, apunta todas las dependencias al mismo puerto: `CORE_BASE_URL=http://localhost:9090`, `FRAUD_BASE_URL=http://localhost:9090` y, en la HU-03, `DIRECTORY_BASE_URL=http://localhost:9090`.

Prueba rápida de que WireMock responde:

```bash
curl -s http://localhost:9090/core/v1/accounts/191-0001234567
curl -s http://localhost:9090/__admin/mappings | grep -c '"name"'
```

## En AWS (dev)

Sin NAT, Fargate no puede descargar WireMock de Docker Hub. Por eso `mocks/Dockerfile` arma una imagen de WireMock con estos stubs y el pipeline del micro la sube al ECR `pragma-transfers-dev-ecr-mocks` (tag `latest`) y reinicia el servicio ECS `mocks`.

El micro la encuentra por Cloud Map en `http://mocks.pragbank.local:8080` (`CORE_BASE_URL`, `FRAUD_BASE_URL` y `DIRECTORY_BASE_URL`). Para cambiar un mock, edita el JSON en `mappings/` y haz merge a `main`.
