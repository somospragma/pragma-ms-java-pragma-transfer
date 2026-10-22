package co.com.pragma.transfers.entrypoint;

import co.com.pragma.transfers.application.MainApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

/** La especificación OpenAPI se genera desde el código e incluye las rutas funcionales. */
@SpringBootTest(classes = MainApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class OpenApiDocsTest {

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void generatesSpecFromRouterOperations() {
        client.get().uri("/v3/api-docs")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.paths['/api/v1/transfers'].post.operationId").isEqualTo("createTransfer")
            .jsonPath("$.paths['/api/v1/transfers/{transferId}'].get.operationId").isEqualTo("getTransfer")
            .jsonPath("$.paths['/api/v1/accounts/{accountId}/transfers'].get.operationId").isEqualTo("listAccountTransfers")
            .jsonPath("$.components.securitySchemes.apiKey.name").isEqualTo("x-api-key")
            .jsonPath("$.components.schemas.CreateTransferRequest").exists();
    }

    @Test
    void servesSwaggerUiAndContracts() {
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
        client.get().uri("/openapi/ms-transfers-v1.yaml").exchange().expectStatus().isOk();
    }
}
