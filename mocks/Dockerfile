# Imagen de mocks para ECS (dev): WireMock con los stubs del repo ya incluidos.
# Sin NAT, Fargate no puede descargar de Docker Hub; por eso el pipeline del micro
# construye esta imagen y la sube al ECR de mocks.
FROM wiremock/wiremock:3.9.1
COPY mappings /home/wiremock/mappings
EXPOSE 8080
