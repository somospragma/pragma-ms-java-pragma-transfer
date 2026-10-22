rootProject.name = "ms-transfers"

include(
    ":domain:model",
    ":domain:ports",
    ":domain:usecases",
    ":infrastructure:driven-adapters:dynamodb-persistence",
    ":infrastructure:driven-adapters:external-api",
    ":infrastructure:driven-adapters:sns-publisher",
    ":infrastructure:entry-points:reactive-web",
    ":infrastructure:helpers",
    ":application:app-service",
)
