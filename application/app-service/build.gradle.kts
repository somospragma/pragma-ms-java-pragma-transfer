apply(plugin = "org.springframework.boot")
apply(plugin = "io.spring.dependency-management")

dependencies {
    implementation(project(":domain:model"))
    implementation(project(":domain:ports"))
    implementation(project(":domain:usecases"))
    implementation(project(":infrastructure:driven-adapters:external-api"))
    implementation(project(":infrastructure:driven-adapters:dynamodb-persistence"))
    implementation(project(":infrastructure:driven-adapters:sns-publisher"))
    implementation(project(":infrastructure:entry-points:reactive-web"))
    implementation(project(":infrastructure:helpers"))

    implementation(libs.boot.starter.webflux)
    implementation(libs.boot.starter.actuator)
    implementation(libs.boot.starter.validation)
    implementation(libs.springdoc.webflux)
    implementation(libs.logstash.encoder)
    implementation(libs.micrometer.registry.prometheus)
    // Lectura del secreto de las APIs al arrancar (ApiKeysSecretEnvironmentPostProcessor)
    implementation(platform(libs.awssdk.bom))
    implementation(libs.awssdk.secretsmanager)
    implementation(libs.awssdk.url.connection.client)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.boot.starter.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.blockhound)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.wiremock.standalone)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Aísla cada clase de test en su propia JVM. Necesario porque BlockHoundTest
// instala BlockHound a nivel de JVM (BeforeAll) y no debe contaminar el resto
// de tests (p.ej. el de integración que levanta el contexto reactivo completo).
tasks.named<Test>("test") {
    setForkEvery(1)
    // Las pruebas de integración levantan WireMock embebido con los stubs reales de mocks/.
    systemProperty("mocks.dir", rootProject.file("mocks").absolutePath)
}

tasks.register<Test>("architectureTest") {
    useJUnitPlatform()
    include("**/HexagonalArchitectureTest.class")
}

tasks.named("build") {
    dependsOn("architectureTest")
}

// Contract-first: los contratos de openapi/ se sirven como estáticos y Swagger UI
// los muestra tal cual (/swagger-ui.html). Las rutas funcionales de WebFlux no
// las descubre springdoc, así que la fuente de la documentación es el contrato.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("openapi")) {
        into("static/openapi")
    }
}
