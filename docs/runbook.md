# Runbook operativo · ms-transfers

Guía operativa del servicio (DOC-BP-005).

## Despliegue
- Lo hace `deployments/azure-pipelines.yml`: build y pruebas → imagen `:latest` a ECR → secretos de la library a Secrets Manager → reinicio de las tareas de ECS Fargate (dev).
- Local: `./gradlew :application:app-service:bootRun` (ver `.env.example`).
- Imagen: `docker build -t ms-transfers:<tag> .` (multi-stage, usuario no root).
- Verificar readiness antes de recibir tráfico: `GET /actuator/health/readiness`.

## Health y observabilidad
- Liveness: `GET /actuator/health/liveness`
- Readiness: `GET /actuator/health/readiness`
- Métricas Prometheus: `GET /actuator/prometheus`
- Logs: JSON a stdout (CloudWatch Logs en ECS), con `traceId` por petición.

## Rollback
- Volver a etiquetar como `latest` la imagen anterior en ECR (cada build también queda con el tag `$(Build.BuildId)`) y reiniciar las tareas con `aws ecs update-service --force-new-deployment`. El servicio no guarda estado en memoria.

## Incidentes comunes
| Síntoma | Diagnóstico | Acción |
|---------|-------------|--------|
| Tareas no arrancan | Falta un secreto, la imagen `:latest` o permisos del task role | Revisar eventos del servicio ECS y el secreto `SECRET_APIS_NAME` |
| Latencia alta hacia el core o antifraude | Dependencia lenta | Revisar los circuit breakers `coreBanking` y `fraud` en métricas |
| Transferencias en `COMPENSATED` | Fallan los créditos en el core | Revisar el core y los eventos `TransferCompensated` |
| Notificaciones no llegan | Mensajes en la DLQ de `notifications` | Revisar la DLQ y el formato del evento |
