# Postman · ms-transfers

`ms-transfers.postman_collection.json` prueba la HU-01 (transferencias) y la HU-03 (pago a celular) contra API Gateway.

1. Importa la colección en Postman.
2. Crea un environment con dos variables:
   - `baseUrl`: URL del stage de API Gateway, por ejemplo `https://xxxx.execute-api.us-east-1.amazonaws.com/dev`.
   - `apiKey`: valor de la API key (tipo *secret*; nunca la subas al repo).
3. Corre la colección completa con el Runner, en orden. Cada carpeta guarda `transferId` y `recipientToken` para los requests siguientes, y cada corrida usa un cliente nuevo (cuenta comodín `191-8…` de los mocks), así el límite diario arranca en cero.

El mismo recorrido por línea de comandos: `python3 scripts/smoke_api.py` (ver `--help`).
