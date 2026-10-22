package co.com.pragma.transfers.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import co.com.pragma.transfers.application.MainApplication;
import co.com.pragma.transfers.infrastructure.dynamodb.memory.InMemoryIdempotencyAdapter;
import co.com.pragma.transfers.infrastructure.dynamodb.memory.InMemoryTransferAdapter;
import co.com.pragma.transfers.infrastructure.sns.InMemoryTransferEventPublisher;
import co.com.pragma.transfers.model.event.TransferEvent;
import co.com.pragma.transfers.model.event.TransferEventType;
import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferChannel;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Escenarios de aceptación de la HU-01 contra el servicio completo: WebFlux real,
 * use cases, adaptadores WebClient con resilience4j y los stubs reales de
 * {@code mocks/mappings} servidos por WireMock embebido en la JVM (sin Docker).
 * Persistencia y eventos en memoria (dynamodb.enabled y sns.enabled en false).
 */
@SpringBootTest(classes = MainApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class TransfersAcceptanceTest {

    private static final String CUSTOMER = "CUST-000123";
    private static final String SOURCE = "191-0001234567";
    private static final String TARGET = "191-0007654321";
    private static final String TARGET_CREDIT_FAILS = "191-0009999999";

    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options()
        .dynamicPort()
        .usingFilesUnderDirectory(System.getProperty("mocks.dir", "../../mocks")));

    static {
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("core-banking.base-url", WIREMOCK::baseUrl);
        registry.add("fraud.base-url", WIREMOCK::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private InMemoryTransferAdapter transfers;

    @Autowired
    private InMemoryIdempotencyAdapter idempotency;

    @Autowired
    private InMemoryTransferEventPublisher events;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        transfers.clear();
        idempotency.clear();
        events.clear();
        WIREMOCK.resetRequests();
        // Escenarios que los stubs compartidos no cubren: score alto y antifraude lento.
        WIREMOCK.stubFor(post(urlEqualTo("/fraud/v1/score")).atPriority(1)
            .withRequestBody(matchingJsonPath("$.amount.value", equalTo("1234.00")))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"operationId\":\"x\",\"score\":91,\"reasons\":[\"NEW_BENEFICIARY\"]}")));
        WIREMOCK.stubFor(post(urlEqualTo("/fraud/v1/score")).atPriority(1)
            .withRequestBody(matchingJsonPath("$.amount.value", equalTo("1111.00")))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withFixedDelay(1500)
                .withBody("{\"operationId\":\"x\",\"score\":10,\"reasons\":[]}")));
    }

    private static Map<String, Object> body(String source, String target, String amount) {
        return Map.of("sourceAccountId", source, "targetAccountId", target,
            "amount", Map.of("value", amount, "currency", "PEN"), "description", "Pago alquiler octubre");
    }

    private WebTestClient.ResponseSpec create(String key, Object body) {
        return createAs(CUSTOMER, key, body);
    }

    private WebTestClient.ResponseSpec createAs(String customer, String key, Object body) {
        return client.post().uri("/api/v1/transfers")
            .header("X-Customer-Id", customer)
            .header("Idempotency-Key", key)
            .header("X-Correlation-Id", "c0ffee00-1234-4abc-9def-000000000001")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange();
    }

    private int debits() {
        return WIREMOCK.findAll(postRequestedFor(urlPathMatching("/core/v1/accounts/.*/debits"))).size();
    }

    private List<TransferEventType> publishedTypes() {
        return events.published().stream().map(TransferEvent::eventType).toList();
    }

    @Test
    void transferenciaExitosaQuedaCompletedYPublicaEvento() {
        create(UUID.randomUUID().toString(), body(SOURCE, TARGET, "150.00"))
            .expectStatus().isCreated()
            .expectHeader().valueMatches("Location", "/api/v1/transfers/[0-9a-f-]{36}")
            .expectHeader().valueEquals("X-Correlation-Id", "c0ffee00-1234-4abc-9def-000000000001")
            .expectBody()
            .jsonPath("$.status").isEqualTo("COMPLETED")
            .jsonPath("$.channel").isEqualTo("ACCOUNT")
            .jsonPath("$.amount.value").isEqualTo("150.00")
            .jsonPath("$.amount.currency").isEqualTo("PEN")
            .jsonPath("$.sourceAccountId").isEqualTo(SOURCE)
            .jsonPath("$.targetAccountId").isEqualTo(TARGET)
            .jsonPath("$.failureCode").doesNotExist();

        assertThat(debits()).isEqualTo(1);
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/core/v1/accounts/" + TARGET + "/credits"))
            .withHeader("X-Correlation-Id", equalTo("c0ffee00-1234-4abc-9def-000000000001"))
            .withRequestBody(matchingJsonPath("$.operationId", com.github.tomakehurst.wiremock.client.WireMock
                .matching("TRF-.*-CREDIT"))));
        assertThat(events.published()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(TransferEventType.TransferCompleted);
            assertThat(event.customerId()).isEqualTo(CUSTOMER);
            assertThat(event.correlationId()).isEqualTo("c0ffee00-1234-4abc-9def-000000000001");
        });
    }

    @Test
    void reglaIncumplidaResponde422SinDebito() {
        create(UUID.randomUUID().toString(), body(SOURCE, TARGET, "10000.01"))
            .expectStatus().isEqualTo(422)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("LIMIT_PER_TX_EXCEEDED")
            .jsonPath("$.status").isEqualTo(422)
            .jsonPath("$.type").isEqualTo("https://api.pragbank.pe/errors/limit-per-tx-exceeded");

        create(UUID.randomUUID().toString(), body(SOURCE, TARGET, "3000.00"))
            .expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");

        assertThat(debits()).isZero();
        assertThat(publishedTypes()).containsExactly(TransferEventType.TransferRejected,
            TransferEventType.TransferRejected);
    }

    @Test
    void cuentasComodinDeLosMocksPermitenProbarLimiteDiarioYSaldoConUnClienteNuevo() {
        // mocks/mappings/core-get-account-smoke-*.json: 191-8… con S/ 50,000 y 191-7… con S/ 100,
        // dueño CUST-{cuenta}. Así el smoke test no arrastra el acumulado diario de otras corridas.
        String rich = "191-8" + String.format("%09d", (long) (Math.random() * 1_000_000_000L));
        String poor = "191-7" + String.format("%09d", (long) (Math.random() * 1_000_000_000L));

        for (int i = 0; i < 3; i++) {
            createAs("CUST-" + rich, UUID.randomUUID().toString(), body(rich, TARGET, "9000.00"))
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.status").isEqualTo("COMPLETED");
        }
        createAs("CUST-" + rich, UUID.randomUUID().toString(), body(rich, TARGET, "3500.00"))
            .expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("DAILY_LIMIT_EXCEEDED");

        createAs("CUST-" + poor, UUID.randomUUID().toString(), body(poor, TARGET, "500.00"))
            .expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void cuentaInexistenteOMonedaDistintaSeRechazan() {
        create(UUID.randomUUID().toString(), body(SOURCE, "191-0000000000", "10.00"))
            .expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("ACCOUNT_INVALID");

        create(UUID.randomUUID().toString(), Map.of("sourceAccountId", SOURCE, "targetAccountId", TARGET,
                "amount", Map.of("value", "10.00", "currency", "USD")))
            .expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("CURRENCY_MISMATCH");
        assertThat(debits()).isZero();
    }

    @Test
    void score67QuedaEnRevisionSinDebito() {
        create(UUID.randomUUID().toString(), body(SOURCE, TARGET, "9500.00"))
            .expectStatus().isCreated()
            .expectBody().jsonPath("$.status").isEqualTo("PENDING_REVIEW");

        assertThat(debits()).isZero();
        assertThat(publishedTypes()).containsExactly(TransferEventType.TransferPendingReview);
    }

    @Test
    void antifraudeLentoDejaLaTransferenciaEnRevision() {
        create(UUID.randomUUID().toString(), body(SOURCE, TARGET, "1111.00"))
            .expectStatus().isCreated()
            .expectBody().jsonPath("$.status").isEqualTo("PENDING_REVIEW");
        assertThat(debits()).isZero();
    }

    @Test
    void scoreAltoSeRechazaPorFraude() {
        create(UUID.randomUUID().toString(), body(SOURCE, TARGET, "1234.00"))
            .expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("FRAUD_REJECTED");
        assertThat(debits()).isZero();
        assertThat(publishedTypes()).containsExactly(TransferEventType.TransferRejected);
    }

    @Test
    void creditoFallidoReversaElDebitoYQuedaCompensated() {
        create(UUID.randomUUID().toString(), body(SOURCE, TARGET_CREDIT_FAILS, "200.00"))
            .expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.status").isEqualTo("COMPENSATED")
            .jsonPath("$.failureCode").isEqualTo("CREDIT_FAILED");

        assertThat(debits()).isEqualTo(1);
        WIREMOCK.verify(1, postRequestedFor(urlPathMatching("/core/v1/debits/DBT-.*/reversal"))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("CREDIT_FAILED"))));
        assertThat(publishedTypes()).containsExactly(TransferEventType.TransferCompensated);
    }

    @Test
    void reintentoIdempotenteDevuelve200ConUnSoloDebito() {
        String key = UUID.randomUUID().toString();
        String transferId = create(key, body(SOURCE, TARGET, "150.00"))
            .expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("transferId").toString();

        create(key, body(SOURCE, TARGET, "150.00"))
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.transferId").isEqualTo(transferId)
            .jsonPath("$.status").isEqualTo("COMPLETED");

        assertThat(debits()).isEqualTo(1);
        assertThat(events.published()).hasSize(1);
    }

    @Test
    void mismaLlaveConOtroCuerpoResponde409() {
        String key = UUID.randomUUID().toString();
        create(key, body(SOURCE, TARGET, "150.00")).expectStatus().isCreated();

        create(key, body(SOURCE, TARGET, "151.00"))
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(debits()).isEqualTo(1);
    }

    @Test
    void consultaResponde200Propia403Ajena404Inexistente() {
        String transferId = create(UUID.randomUUID().toString(), body(SOURCE, TARGET, "150.00"))
            .expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("transferId").toString();

        client.get().uri("/api/v1/transfers/{id}", transferId).header("X-Customer-Id", CUSTOMER)
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.transferId").isEqualTo(transferId);

        client.get().uri("/api/v1/transfers/{id}", transferId).header("X-Customer-Id", "CUST-000456")
            .exchange()
            .expectStatus().isForbidden()
            .expectBody().jsonPath("$.code").isEqualTo("ACCOUNT_NOT_OWNED");

        client.get().uri("/api/v1/transfers/{id}", UUID.randomUUID()).header("X-Customer-Id", CUSTOMER)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.code").isEqualTo("TRANSFER_NOT_FOUND");
    }

    @Test
    void historialPaginaVeinteDeCuarentaYCincoDelMasRecienteAlMasAntiguo() {
        Instant base = Instant.parse("2026-10-01T10:00:00Z");
        for (int i = 0; i < 45; i++) {
            transfers.save(seed(base.plusSeconds(60L * i))).block();
        }

        client.get().uri("/api/v1/accounts/{id}/transfers?page=0&size=20", SOURCE)
            .header("X-Customer-Id", CUSTOMER)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(20)
            .jsonPath("$.page").isEqualTo(0)
            .jsonPath("$.size").isEqualTo(20)
            .jsonPath("$.totalElements").isEqualTo(45)
            .jsonPath("$.totalPages").isEqualTo(3)
            .jsonPath("$.content[0].createdAt").isEqualTo(base.plusSeconds(60L * 44).toString())
            .jsonPath("$.content[19].createdAt").isEqualTo(base.plusSeconds(60L * 25).toString());

        client.get().uri("/api/v1/accounts/{id}/transfers?page=2&size=20", SOURCE)
            .header("X-Customer-Id", CUSTOMER)
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.content.length()").isEqualTo(5);
    }

    @Test
    void historialDeCuentaAjenaResponde403() {
        client.get().uri("/api/v1/accounts/{id}/transfers", TARGET)
            .header("X-Customer-Id", CUSTOMER)
            .exchange()
            .expectStatus().isForbidden()
            .expectBody().jsonPath("$.code").isEqualTo("ACCOUNT_NOT_OWNED");
    }

    @Test
    void cuentaOrigenAjenaResponde403SinDebito() {
        client.post().uri("/api/v1/transfers")
            .header("X-Customer-Id", "CUST-000999")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body(SOURCE, TARGET, "150.00"))
            .exchange()
            .expectStatus().isForbidden()
            .expectBody().jsonPath("$.code").isEqualTo("ACCOUNT_NOT_OWNED");
        assertThat(debits()).isZero();
    }

    @Test
    void validacionesDeEntrada() {
        create(UUID.randomUUID().toString(), body(SOURCE, TARGET, "10.999"))
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("AMOUNT_INVALID")
            .jsonPath("$.errors[0].field").isEqualTo("amount.value");

        create(UUID.randomUUID().toString(), body(SOURCE, TARGET, "0.00"))
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("AMOUNT_INVALID");

        create("no-es-uuid", body(SOURCE, TARGET, "10.00"))
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");

        create(UUID.randomUUID().toString(), Map.of("sourceAccountId", "123", "targetAccountId", TARGET,
                "amount", Map.of("value", "10.00", "currency", "PEN")))
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].field").isEqualTo("sourceAccountId");

        create(UUID.randomUUID().toString(), Map.of("sourceAccountId", SOURCE, "targetAccountId", TARGET,
                "amount", Map.of("value", "10.00", "currency", "PEN"), "extra", "x"))
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");

        client.post().uri("/api/v1/transfers")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body(SOURCE, TARGET, "10.00"))
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");

        client.get().uri("/api/v1/accounts/{id}/transfers?size=101", SOURCE)
            .header("X-Customer-Id", CUSTOMER)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");

        assertThat(debits()).isZero();
    }

    @Test
    void validacionesDeHeadersYParametros() {
        client.post().uri("/api/v1/transfers")
            .header("X-Customer-Id", CUSTOMER)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body(SOURCE, TARGET, "10.00"))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].field").isEqualTo("Idempotency-Key");

        client.post().uri("/api/v1/transfers")
            .header("X-Customer-Id", CUSTOMER)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");

        client.get().uri("/api/v1/transfers/{id}", UUID.randomUUID()).header("X-Customer-Id", "CUST 1;drop")
            .exchange()
            .expectStatus().isBadRequest();

        client.get().uri("/api/v1/transfers/no-es-uuid").header("X-Customer-Id", CUSTOMER)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.code").isEqualTo("TRANSFER_NOT_FOUND");

        for (String query : new String[] {"page=x", "status=PAID", "from=2026-13-01", "to=ayer",
            "from=2026-10-02&to=2026-10-01"}) {
            client.get().uri("/api/v1/accounts/" + SOURCE + "/transfers?" + query)
                .header("X-Customer-Id", CUSTOMER)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        }

        client.get().uri("/api/v1/accounts/191-1/transfers").header("X-Customer-Id", CUSTOMER)
            .exchange()
            .expectStatus().isBadRequest();

        client.get().uri("/api/v1/accounts/{id}/transfers?status=COMPLETED&from=2026-10-01&to=2026-10-31", SOURCE)
            .header("X-Customer-Id", CUSTOMER)
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.totalElements").isEqualTo(0);
    }

    private static Transfer seed(Instant createdAt) {
        return Transfer.builder()
            .transferId(UUID.randomUUID().toString())
            .idempotencyKey(UUID.randomUUID().toString())
            .customerId(CUSTOMER)
            .sourceAccountId(SOURCE)
            .targetAccountId(TARGET)
            .amount(new Money(new BigDecimal("10.00"), "PEN"))
            .status(TransferStatus.COMPLETED)
            .channel(TransferChannel.ACCOUNT)
            .businessDate(LocalDate.of(2026, 10, 1))
            .createdAt(createdAt)
            .updatedAt(createdAt)
            .build();
    }
}
