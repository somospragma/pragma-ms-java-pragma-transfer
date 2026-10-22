package co.com.pragma.transfers.entrypoint;

import co.com.pragma.transfers.application.MainApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Prueba de humo del scaffold: el contexto completo arranca sin AWS
 * (dynamodb.enabled y sns.enabled en false), expone health y responde errores
 * en RFC 7807 con los headers de seguridad.
 */
@SpringBootTest(classes = MainApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class BaseEndpointsIntegrationTest {

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void shouldExposeLivenessHealth() {
        client.get().uri("/actuator/health/liveness")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void shouldExposeReadinessHealth() {
        client.get().uri("/actuator/health/readiness")
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void shouldReturnRfc7807ProblemForUnknownRoute() {
        client.get().uri("/api/v1/unknown")
            .exchange()
            .expectStatus().isNotFound()
            .expectHeader().contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND");
    }

    @Test
    void shouldSetSecurityAndCorrelationHeaders() {
        client.get().uri("/actuator/health/liveness")
            .exchange()
            .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
            .expectHeader().valueEquals("X-Frame-Options", "DENY")
            .expectHeader().exists("Content-Security-Policy")
            .expectHeader().exists("Strict-Transport-Security")
            .expectHeader().exists("X-Request-ID")
            .expectHeader().exists("X-Correlation-Id");
    }
}
