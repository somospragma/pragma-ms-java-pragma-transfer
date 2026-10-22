// domain/model — Java puro + Lombok (heredados del root). Sin dependencias de producción adicionales.
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
