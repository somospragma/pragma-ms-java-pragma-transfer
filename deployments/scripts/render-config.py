#!/usr/bin/env python3
"""
Reemplaza en application.yml el valor por defecto de cada ${VARIABLE:default} con el valor
de la library (Azure DevOps expone las variables no secretas como variables de entorno).

- Solo toca las variables de ALLOWED. Las llaves de las APIs nunca: viven en Secrets Manager.
- Si la variable no está en la library o está vacía, queda el valor por defecto local.
- Una variable de entorno en la tarea de ECS sigue teniendo prioridad sobre el valor del archivo.

Uso: python3 deployments/scripts/render-config.py application/app-service/src/main/resources/application.yml
"""

import os
import re
import sys

ALLOWED = [
    "AWS_REGION",
    "SECRET_APIS_NAME",
    "DYNAMODB_ENABLED",
    "DYNAMODB_TABLE_TRANSFERS",
    "DYNAMODB_TABLE_IDEMPOTENCY",
    "DYNAMODB_TABLE_RECIPIENT_TOKENS",
    "SNS_ENABLED",
    "SNS_TOPIC_ARN",
    "CORE_BASE_URL",
    "FRAUD_BASE_URL",
    "DIRECTORY_BASE_URL",
]


def main():
    if len(sys.argv) != 2:
        print("Uso: render-config.py <application.yml>", file=sys.stderr)
        sys.exit(1)
    path = sys.argv[1]
    with open(path, encoding="utf-8") as f:
        content = f.read()

    for name in ALLOWED:
        value = os.environ.get(name, "").strip()
        # Variable sin definir en Azure DevOps: llega literal como $(NOMBRE)
        if not value or value.startswith("$("):
            print(f">>> {name}: sin valor en la library, queda el valor por defecto")
            continue
        pattern = re.compile(r"\$\{" + re.escape(name) + r":[^}]*\}")
        content, count = pattern.subn(lambda _: "${%s:%s}" % (name, value), content)
        if count:
            print(f">>> {name} = {value}")
        else:
            print(f">>> {name}: no se usa en este application.yml")

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


if __name__ == "__main__":
    main()
