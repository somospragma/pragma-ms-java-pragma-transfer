#!/usr/bin/env python3
"""
Smoke test de ms-transfers desplegado detrás de API Gateway (HU-01 y HU-03).

Recorre la API de punta a punta con los datos de los mocks (mocks/README.md) y al final
imprime una tabla con lo que pasó y lo que no. Solo usa la librería estándar de Python 3.

Uso:
    export API_URL=https://xxxx.execute-api.us-east-1.amazonaws.com/dev
    export API_KEY=...
    python3 scripts/smoke_api.py

    # o por argumentos
    python3 scripts/smoke_api.py --url https://... --api-key ...

    # solo una historia
    python3 scripts/smoke_api.py --only HU-01

Opciones:
    --fixed-customer  Usa el cliente fijo CUST-000123 (cuenta 191-0001234567) en vez de uno nuevo.
                   Por defecto cada corrida inventa una cuenta 191-8xxxxxxxxx y su cliente
                   CUST-191-8xxxxxxxxx (stubs comodín de mocks/), así el límite diario arranca en cero.
    --skip-fraud   No corre el caso de antifraude (S/ 9,500.00 en PENDING_REVIEW). Ese monto
                   suma al límite diario del cliente (S/ 30,000.00); con varias corridas en el
                   mismo día las transferencias empiezan a fallar con DAILY_LIMIT_EXCEEDED.
    --verbose      Muestra el cuerpo de cada respuesta.

Código de salida: 0 si todo pasó, 1 si algo falló.
"""

import argparse
import json
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

# Datos de los mocks (mocks/README.md)
CUSTOMER = "CUST-000123"
OTHER_CUSTOMER = "CUST-000999"
SOURCE = "191-0001234567"          # del cliente, S/ 2,500.00
TARGET = "191-0007654321"          # de otro cliente (Mario Quiroz)
TARGET_CREDIT_FAILS = "191-0009999999"  # el crédito responde 503 → compensación
UNKNOWN_ACCOUNT = "191-0000000001"
PHONE_PRAGBANK = "+51987654321"    # Mario Quiroz → 191-0007654321
PHONE_OWN = "+51999111222"         # es de la cuenta origen → SELF_TRANSFER
PHONE_OTHER_BANK = "+51912345678"  # otro banco → RECIPIENT_NOT_SUPPORTED
PHONE_NOT_FOUND = "+51955555555"   # no afiliado → RECIPIENT_NOT_FOUND

# Cliente de la corrida: lo fija main(). Con los stubs comodín de mocks/ una cuenta 191-8… existe con
# S/ 50,000 y su dueño es CUST-{cuenta}; una 191-7… tiene S/ 100.
RUN = {"customer": CUSTOMER, "source": SOURCE}


def wildcard_account(prefix):
    return prefix + f"{random.randrange(10 ** 9):09d}"

GREEN, RED, YELLOW, DIM, BOLD, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[1m", "\033[0m"


class Response:
    def __init__(self, status, headers, body):
        self.status = status
        self.headers = headers
        self.text = body
        try:
            self.json = json.loads(body) if body else {}
        except ValueError:
            self.json = {}

    def code(self):
        return self.json.get("code") if isinstance(self.json, dict) else None


class Api:
    def __init__(self, base_url, api_key, verbose=False):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.verbose = verbose

    def call(self, method, path, customer="run", body=None, idempotency_key=None,
             api_key="default", query=None):
        url = self.base_url + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        headers = {"Accept": "application/json", "X-Correlation-Id": str(uuid.uuid4())}
        if customer == "run":
            customer = RUN["customer"]
        key = self.api_key if api_key == "default" else api_key
        if key:
            headers["x-api-key"] = key
        if customer:
            headers["X-Customer-Id"] = customer
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                result = Response(response.status, dict(response.headers), response.read().decode())
        except urllib.error.HTTPError as error:
            result = Response(error.code, dict(error.headers), error.read().decode())
        if self.verbose:
            print(f"{DIM}    {method} {path} → {result.status} {result.text[:400]}{RESET}")
        return result


class Runner:
    def __init__(self, only):
        self.only = only
        self.results = []
        self.context = {}

    def run(self, story, name, expected, check):
        """check() devuelve (ok, obtenido, detalle). Una excepción cuenta como fallo."""
        if self.only and story != self.only:
            return
        started = time.time()
        try:
            ok, got, detail = check()
        except Exception as error:  # noqa: BLE001 - el smoke reporta cualquier error como fallo
            ok, got, detail = False, "excepción", f"{type(error).__name__}: {error}"
        elapsed = int((time.time() - started) * 1000)
        self.results.append((story, name, expected, got, ok, detail, elapsed))
        mark = f"{GREEN}PASA{RESET}" if ok else f"{RED}FALLA{RESET}"
        print(f"  [{mark}] {story} · {name} {DIM}({elapsed} ms){RESET}")
        if not ok and detail:
            print(f"         {YELLOW}{detail}{RESET}")

    def skip(self, story, name, reason):
        if self.only and story != self.only:
            return
        self.results.append((story, name, "-", "omitida", None, reason, 0))
        print(f"  [{YELLOW}OMITE{RESET}] {story} · {name} {DIM}({reason}){RESET}")


def expect(response, status, code=None, extra=None):
    """Compara status y código de error; extra(response) devuelve un texto de error o None."""
    got = f"{response.status}" + (f" {response.code()}" if response.code() else "")
    problems = []
    if response.status != status:
        problems.append(f"esperaba HTTP {status}")
    if code and response.code() != code:
        problems.append(f"esperaba code {code}")
    if not problems and extra:
        message = extra(response)
        if message:
            problems.append(message)
    detail = "; ".join(problems)
    if problems and response.code():
        detail += f" · detail: {response.json.get('detail', '')}"
    elif problems and response.status >= 400 and not response.code():
        detail += f" · cuerpo: {response.text[:200]}"
    return (not problems, got, detail)


def transfer_body(amount, target=TARGET, source=None, currency="PEN", description="smoke test"):
    return {"sourceAccountId": source or RUN["source"], "targetAccountId": target,
            "amount": {"value": amount, "currency": currency}, "description": description}


def phone_body(token, amount="45.50", currency="PEN", source=None):
    return {"sourceAccountId": source or RUN["source"], "recipientToken": token,
            "amount": {"value": amount, "currency": currency}, "description": "smoke test celular"}


def new_key():
    return str(uuid.uuid4())


def platform_checks(api, r):
    r.run("Infra", "Sin x-api-key responde 403 (API Gateway)", "403",
          lambda: expect(api.call("GET", "/actuator/health", api_key=None), 403))
    r.run("Infra", "Con x-api-key inválida responde 403", "403",
          lambda: expect(api.call("GET", "/actuator/health", api_key="llave-invalida"), 403))
    r.run("Infra", "Health del micro", "200 UP",
          lambda: expect(api.call("GET", "/actuator/health"), 200,
                         extra=lambda x: None if x.json.get("status") == "UP" else f"status={x.json.get('status')}"))


def hu01_checks(api, r, skip_fraud, fixed):
    ctx = r.context

    r.run("HU-01", "Sin X-Customer-Id responde 401", "401 UNAUTHORIZED",
          lambda: expect(api.call("POST", "/api/v1/transfers", customer=None, body=transfer_body("10.00"),
                                  idempotency_key=new_key()), 401, "UNAUTHORIZED"))

    def create_ok():
        ctx["key"] = new_key()
        ctx["body"] = transfer_body("150.00", description="smoke alquiler")
        response = api.call("POST", "/api/v1/transfers", body=ctx["body"], idempotency_key=ctx["key"])
        ctx["transferId"] = response.json.get("transferId")

        def extra(x):
            if x.json.get("status") != "COMPLETED":
                return f"status={x.json.get('status')} failureCode={x.json.get('failureCode')}"
            if not any(k.lower() == "location" for k in x.headers):
                return "falta el header Location"
            return None
        return expect(response, 201, extra=extra)
    r.run("HU-01", "Transferencia exitosa S/ 150.00", "201 COMPLETED + Location", create_ok)

    def idempotent_repeat():
        response = api.call("POST", "/api/v1/transfers", body=ctx["body"], idempotency_key=ctx["key"])
        return expect(response, 200, extra=lambda x: None if x.json.get("transferId") == ctx.get("transferId")
                      else "devolvió otra transferencia")
    r.run("HU-01", "Misma Idempotency-Key y mismo cuerpo", "200 misma transferencia", idempotent_repeat)

    r.run("HU-01", "Misma Idempotency-Key con otro monto", "409 IDEMPOTENCY_KEY_REUSED",
          lambda: expect(api.call("POST", "/api/v1/transfers", body=transfer_body("151.00"),
                                  idempotency_key=ctx["key"]), 409, "IDEMPOTENCY_KEY_REUSED"))

    r.run("HU-01", "Consultar la transferencia", "200 COMPLETED",
          lambda: expect(api.call("GET", f"/api/v1/transfers/{ctx['transferId']}"), 200,
                         extra=lambda x: None if x.json.get("status") == "COMPLETED" else f"status={x.json.get('status')}"))

    r.run("HU-01", "Consultar la transferencia como otro cliente", "403 ACCOUNT_NOT_OWNED",
          lambda: expect(api.call("GET", f"/api/v1/transfers/{ctx['transferId']}", customer=OTHER_CUSTOMER),
                         403, "ACCOUNT_NOT_OWNED"))

    r.run("HU-01", "Consultar una transferencia inexistente", "404 TRANSFER_NOT_FOUND",
          lambda: expect(api.call("GET", f"/api/v1/transfers/{uuid.uuid4()}"), 404, "TRANSFER_NOT_FOUND"))

    def history():
        response = api.call("GET", f"/api/v1/accounts/{RUN['source']}/transfers", query={"page": 0, "size": 20})

        def extra(x):
            ids = [t.get("transferId") for t in x.json.get("content", [])]
            return None if ctx.get("transferId") in ids else "la transferencia creada no aparece en el historial"
        return expect(response, 200, extra=extra)
    r.run("HU-01", "Historial de la cuenta origen", "200 incluye la transferencia", history)

    r.run("HU-01", "Historial de una cuenta ajena", "403 ACCOUNT_NOT_OWNED",
          lambda: expect(api.call("GET", f"/api/v1/accounts/{TARGET}/transfers"), 403, "ACCOUNT_NOT_OWNED"))

    r.run("HU-01", "Monto con 3 decimales", "400 AMOUNT_INVALID",
          lambda: expect(api.call("POST", "/api/v1/transfers", body=transfer_body("10.001"),
                                  idempotency_key=new_key()), 400, "AMOUNT_INVALID"))

    r.run("HU-01", "Cuenta origen de otro cliente", "403 ACCOUNT_NOT_OWNED",
          lambda: expect(api.call("POST", "/api/v1/transfers", body=transfer_body("10.00", source=TARGET, target=RUN["source"]),
                                  idempotency_key=new_key()), 403, "ACCOUNT_NOT_OWNED"))

    r.run("HU-01", "Monto mayor a S/ 10,000.00", "422 LIMIT_PER_TX_EXCEEDED",
          lambda: expect(api.call("POST", "/api/v1/transfers", body=transfer_body("10000.01"),
                                  idempotency_key=new_key()), 422, "LIMIT_PER_TX_EXCEEDED"))

    r.run("HU-01", "Cuenta destino inexistente", "422 ACCOUNT_INVALID",
          lambda: expect(api.call("POST", "/api/v1/transfers", body=transfer_body("10.00", target=UNKNOWN_ACCOUNT),
                                  idempotency_key=new_key()), 422, "ACCOUNT_INVALID"))

    def insufficient_funds():
        if fixed:
            return expect(api.call("POST", "/api/v1/transfers", body=transfer_body("3000.00"),
                                   idempotency_key=new_key()), 422, "INSUFFICIENT_FUNDS")
        poor = wildcard_account("191-7")  # S/ 100.00 en el mock
        return expect(api.call("POST", "/api/v1/transfers", customer=f"CUST-{poor}",
                               body=transfer_body("500.00", source=poor), idempotency_key=new_key()),
                      422, "INSUFFICIENT_FUNDS")
    r.run("HU-01", "Saldo insuficiente", "422 INSUFFICIENT_FUNDS", insufficient_funds)

    def daily_limit():
        rich = wildcard_account("191-8")  # cliente nuevo, acumulado del día en cero
        customer = f"CUST-{rich}"
        for _ in range(3):
            response = api.call("POST", "/api/v1/transfers", customer=customer,
                                body=transfer_body("9000.00", source=rich), idempotency_key=new_key())
            if response.status != 201:
                ok, got, detail = expect(response, 201)
                return False, got, f"la transferencia previa de S/ 9,000.00 no pasó: {detail}"
        return expect(api.call("POST", "/api/v1/transfers", customer=customer,
                               body=transfer_body("3500.00", source=rich), idempotency_key=new_key()),
                      422, "DAILY_LIMIT_EXCEEDED")
    if fixed:
        r.skip("HU-01", "Límite diario S/ 30,000 (3 × 9,000 + 3,500)", "necesita las cuentas comodín (sin --fixed-customer)")
    else:
        r.run("HU-01", "Límite diario S/ 30,000 (3 × 9,000 + 3,500)", "422 DAILY_LIMIT_EXCEEDED", daily_limit)

    r.run("HU-01", "Crédito falla → reversa del débito", "201 COMPENSATED CREDIT_FAILED",
          lambda: expect(api.call("POST", "/api/v1/transfers", body=transfer_body("200.00", target=TARGET_CREDIT_FAILS),
                                  idempotency_key=new_key()), 201,
                         extra=lambda x: None if (x.json.get("status"), x.json.get("failureCode")) == ("COMPENSATED", "CREDIT_FAILED")
                         else f"status={x.json.get('status')} failureCode={x.json.get('failureCode')}"))

    if skip_fraud:
        r.skip("HU-01", "Antifraude score 67 (S/ 9,500.00)", "--skip-fraud")
    else:
        r.run("HU-01", "Antifraude score 67 (S/ 9,500.00)", "201 PENDING_REVIEW",
              lambda: expect(api.call("POST", "/api/v1/transfers", body=transfer_body("9500.00"),
                                      idempotency_key=new_key()), 201,
                             extra=lambda x: None if x.json.get("status") == "PENDING_REVIEW" else f"status={x.json.get('status')}"))


def resolve(api, phone, customer="run"):
    return api.call("GET", "/api/v1/phone-transfers/recipients", customer=customer, query={"phone": phone})


def hu03_checks(api, r):
    ctx = r.context

    def resolve_ok():
        response = resolve(api, PHONE_PRAGBANK)
        ctx["token"] = response.json.get("recipientToken")

        def extra(x):
            body = x.json
            if not str(body.get("recipientToken", "")).startswith("rt_"):
                return "recipientToken no empieza con rt_"
            if "targetAccountId" in body or TARGET in x.text:
                return "la respuesta expone la cuenta destino"
            if not body.get("maskedName") or "Quiroz" in x.text:
                return f"nombre no enmascarado: {body.get('maskedName')}"
            return None
        return expect(response, 200, extra=extra)
    r.run("HU-03", "Resolver celular PragBank", "200 token + nombre enmascarado", resolve_ok)

    r.run("HU-03", "Celular con formato inválido", "400 PHONE_INVALID",
          lambda: expect(resolve(api, "987654321"), 400, "PHONE_INVALID"))
    r.run("HU-03", "Celular no afiliado", "404 RECIPIENT_NOT_FOUND",
          lambda: expect(resolve(api, PHONE_NOT_FOUND), 404, "RECIPIENT_NOT_FOUND"))
    r.run("HU-03", "Celular de otro banco", "422 RECIPIENT_NOT_SUPPORTED",
          lambda: expect(resolve(api, PHONE_OTHER_BANK), 422, "RECIPIENT_NOT_SUPPORTED"))
    r.run("HU-03", "Resolver sin X-Customer-Id", "401 UNAUTHORIZED",
          lambda: expect(resolve(api, PHONE_PRAGBANK, customer=None), 401, "UNAUTHORIZED"))

    # Rechazos previos: no consumen el token
    r.run("HU-03", "Pago mayor a S/ 500.00 (no consume el token)", "422 LIMIT_PER_TX_EXCEEDED",
          lambda: expect(api.call("POST", "/api/v1/phone-transfers", body=phone_body(ctx["token"], "500.01"),
                                  idempotency_key=new_key()), 422, "LIMIT_PER_TX_EXCEEDED"))
    r.run("HU-03", "Pago en USD (no consume el token)", "422 LIMIT_PER_TX_EXCEEDED",
          lambda: expect(api.call("POST", "/api/v1/phone-transfers", body=phone_body(ctx["token"], "10.00", "USD"),
                                  idempotency_key=new_key()), 422, "LIMIT_PER_TX_EXCEEDED"))
    r.run("HU-03", "Cuenta origen de otro cliente (no consume el token)", "403 ACCOUNT_NOT_OWNED",
          lambda: expect(api.call("POST", "/api/v1/phone-transfers", body=phone_body(ctx["token"], source=TARGET_CREDIT_FAILS),
                                  idempotency_key=new_key()), 403, "ACCOUNT_NOT_OWNED"))

    def pay_ok():
        ctx["phone_key"] = new_key()
        ctx["phone_body"] = phone_body(ctx["token"])
        response = api.call("POST", "/api/v1/phone-transfers", body=ctx["phone_body"], idempotency_key=ctx["phone_key"])
        ctx["phoneTransferId"] = response.json.get("transferId")

        def extra(x):
            body = x.json
            if body.get("status") != "COMPLETED":
                return f"status={body.get('status')} failureCode={body.get('failureCode')}"
            if body.get("channel") != "PHONE":
                return f"channel={body.get('channel')}"
            if body.get("targetPhoneMasked") != "+51 9** *** 321":
                return f"targetPhoneMasked={body.get('targetPhoneMasked')}"
            if body.get("targetAccountId"):
                return "la respuesta expone targetAccountId"
            return None
        return expect(response, 201, extra=extra)
    r.run("HU-03", "Pago a celular S/ 45.50 con el mismo token", "201 COMPLETED PHONE", pay_ok)

    r.run("HU-03", "Repetir el pago con la misma llave", "200 mismo pago",
          lambda: expect(api.call("POST", "/api/v1/phone-transfers", body=ctx["phone_body"],
                                  idempotency_key=ctx["phone_key"]), 200,
                         extra=lambda x: None if x.json.get("transferId") == ctx.get("phoneTransferId") else "devolvió otro pago"))

    r.run("HU-03", "Reusar el token con otra llave (uso único)", "422 RECIPIENT_TOKEN_EXPIRED",
          lambda: expect(api.call("POST", "/api/v1/phone-transfers", body=ctx["phone_body"],
                                  idempotency_key=new_key()), 422, "RECIPIENT_TOKEN_EXPIRED"))

    r.run("HU-03", "Token inexistente", "422 RECIPIENT_TOKEN_EXPIRED",
          lambda: expect(api.call("POST", "/api/v1/phone-transfers", body=phone_body("rt_no_existe"),
                                  idempotency_key=new_key()), 422, "RECIPIENT_TOKEN_EXPIRED"))

    def token_other_customer():
        token = resolve(api, PHONE_PRAGBANK).json.get("recipientToken")
        return expect(api.call("POST", "/api/v1/phone-transfers", customer=OTHER_CUSTOMER,
                               body=phone_body(token, source=TARGET_CREDIT_FAILS), idempotency_key=new_key()),
                      422, "RECIPIENT_TOKEN_EXPIRED")
    r.run("HU-03", "Token de otro cliente", "422 RECIPIENT_TOKEN_EXPIRED", token_other_customer)

    def self_transfer():
        # +51999111222 es de la cuenta fija 191-0001234567 (CUST-000123); el rechazo no suma al límite diario
        token = resolve(api, PHONE_OWN, customer=CUSTOMER).json.get("recipientToken")
        return expect(api.call("POST", "/api/v1/phone-transfers", customer=CUSTOMER,
                               body=phone_body(token, source=SOURCE), idempotency_key=new_key()),
                      422, "SELF_TRANSFER")
    r.run("HU-03", "Pagarme a mi propio celular", "422 SELF_TRANSFER", self_transfer)

    r.run("HU-03", "Consultar el pago a celular", "200 channel PHONE",
          lambda: expect(api.call("GET", f"/api/v1/transfers/{ctx['phoneTransferId']}"), 200,
                         extra=lambda x: None if x.json.get("channel") == "PHONE" else f"channel={x.json.get('channel')}"))


def print_summary(results):
    print()
    headers = ("#", "HU", "Prueba", "Esperado", "Obtenido", "Resultado")
    rows = []
    for index, (story, name, expected, got, ok, _detail, _ms) in enumerate(results, 1):
        state = "PASA" if ok else ("OMITIDA" if ok is None else "FALLA")
        rows.append((str(index), story, name, expected, got, state))
    widths = [max(len(h), *(len(row[i]) for row in rows)) for i, h in enumerate(headers)]

    def line(cells, color_last=False):
        out = []
        for i, cell in enumerate(cells):
            text = cell.ljust(widths[i])
            if color_last and i == len(cells) - 1:
                color = GREEN if cell == "PASA" else (YELLOW if cell == "OMITIDA" else RED)
                text = f"{color}{text}{RESET}"
            out.append(text)
        return "| " + " | ".join(out) + " |"

    print(line(headers))
    print("|-" + "-|-".join("-" * w for w in widths) + "-|")
    for row in rows:
        print(line(row, color_last=True))

    passed = sum(1 for r in results if r[4] is True)
    failed = sum(1 for r in results if r[4] is False)
    skipped = sum(1 for r in results if r[4] is None)
    print()
    print(f"{BOLD}Total: {len(results)} · {GREEN}pasan {passed}{RESET}{BOLD} · {RED}fallan {failed}{RESET}"
          f"{BOLD} · {YELLOW}omitidas {skipped}{RESET}")
    if any("DAILY_LIMIT_EXCEEDED" in (r[3] or "") and r[4] is False for r in results):
        print(f"{YELLOW}Ojo: hubo DAILY_LIMIT_EXCEEDED. El cliente {RUN['customer']} superó S/ 30,000.00 hoy "
              f"(hora de Lima) por corridas anteriores; corre sin --fixed-customer o espera al día siguiente.{RESET}")
    return failed


def main():
    parser = argparse.ArgumentParser(description="Smoke test de ms-transfers vía API Gateway (HU-01 y HU-03)")
    parser.add_argument("--url", default=os.environ.get("API_URL"), help="URL del stage, p. ej. https://xxxx.execute-api.us-east-1.amazonaws.com/dev (o API_URL)")
    parser.add_argument("--api-key", default=os.environ.get("API_KEY"), help="Valor de la API key (o API_KEY)")
    parser.add_argument("--only", choices=["Infra", "HU-01", "HU-03"], help="Correr solo un grupo")
    parser.add_argument("--skip-fraud", action="store_true", help="Omitir el caso de S/ 9,500.00 (PENDING_REVIEW)")
    parser.add_argument("--fixed-customer", action="store_true",
                        help="Usar el cliente fijo CUST-000123 en vez de uno nuevo por corrida")
    parser.add_argument("--verbose", action="store_true", help="Mostrar el cuerpo de cada respuesta")
    args = parser.parse_args()

    if not args.url or not args.api_key:
        parser.error("faltan la URL y la API key: usa --url/--api-key o las variables API_URL/API_KEY")

    api = Api(args.url, args.api_key, args.verbose)
    runner = Runner(args.only)
    if not args.fixed_customer:
        RUN["source"] = wildcard_account("191-8")
        RUN["customer"] = f"CUST-{RUN['source']}"
    print(f"{BOLD}ms-transfers smoke test{RESET} → {args.url}")
    print(f"Cliente de la corrida: {RUN['customer']} · cuenta origen {RUN['source']}\n")

    platform_checks(api, runner)
    hu01_checks(api, runner, args.skip_fraud, args.fixed_customer)
    hu03_checks(api, runner)

    failed = print_summary(runner.results)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
