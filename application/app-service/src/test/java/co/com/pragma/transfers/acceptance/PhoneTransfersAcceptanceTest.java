package co.com.pragma.transfers.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import co.com.pragma.transfers.application.MainApplication;
import co.com.pragma.transfers.infrastructure.dynamodb.memory.InMemoryIdempotencyAdapter;
import co.com.pragma.transfers.infrastructure.dynamodb.memory.InMemoryRecipientTokenAdapter;
import co.com.pragma.transfers.infrastructure.dynamodb.memory.InMemoryTransferAdapter;
import co.com.pragma.transfers.infrastructure.sns.InMemoryTransferEventPublisher;
import co.com.pragma.transfers.model.event.TransferEvent;
import co.com.pragma.transfers.model.event.TransferEventType;
import co.com.pragma.transfers.model.phone.RecipientToken;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Escenarios de aceptación de la HU-03 (pago a celular) contra el servicio completo:
 * WebFlux real, use cases, adaptadores WebClient (core, antifraude y directorio de
 * alias) con los stubs reales de {@code mocks/mappings} en WireMock embebido (sin
 * Docker). Tablas y eventos en memoria. El reloj se puede adelantar para probar el
 * vencimiento del recipientToken (RN-12) sin esperar 5 minutos.
 */
@SpringBootTest(classes = MainApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PhoneTransfersAcceptanceTest.ClockConfig.class)
class PhoneTransfersAcceptanceTest {

    private static final String CUSTOMER = "CUST-000123";
    private static final String SOURCE = "191-0001234567";
    private static final String MARIO_PHONE = "+51987654321";
    private static final String MARIO_ACCOUNT = "191-0007654321";
    private static final String OWN_PHONE = "+51999111222";
    private static final String OTHER_BANK_PHONE = "+51912345678";
    private static final String NOT_AFFILIATED_PHONE = "+51955555555";

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
        registry.add("directory.base-url", WIREMOCK::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    /** Reloj del sistema con un desfase que la prueba puede adelantar. */
    static final class MovableClock extends Clock {

        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        void advance(Duration duration) {
            offset.updateAndGet(current -> current.plus(duration));
        }

        void reset() {
            offset.set(Duration.ZERO);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset.get());
        }
    }

    @TestConfiguration
    static class ClockConfig {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private InMemoryTransferAdapter transfers;

    @Autowired
    private InMemoryIdempotencyAdapter idempotency;

    @Autowired
    private InMemoryRecipientTokenAdapter tokens;

    @Autowired
    private InMemoryTransferEventPublisher events;

    @Autowired
    private MovableClock clock;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        transfers.clear();
        idempotency.clear();
        tokens.clear();
        events.clear();
        clock.reset();
        WIREMOCK.resetRequests();
    }

    private WebTestClient.ResponseSpec resolve(String phone) {
        // Variable de plantilla: el '+' viaja codificado (%2B), como debe enviarlo un cliente.
        return client.get().uri("/api/v1/phone-transfers/recipients?phone={phone}", phone)
            .header("X-Customer-Id", CUSTOMER)
            .exchange();
    }

    private String tokenFor(String phone) {
        return resolve(phone).expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("recipientToken").toString();
    }

    private static Map<String, Object> body(String token, String amount, String currency) {
        return Map.of("sourceAccountId", SOURCE, "recipientToken", token,
            "amount", Map.of("value", amount, "currency", currency), "description", "Almuerzo");
    }

    private WebTestClient.ResponseSpec pay(String key, Object body) {
        return client.post().uri("/api/v1/phone-transfers")
            .header("X-Customer-Id", CUSTOMER)
            .header("Idempotency-Key", key)
            .header("X-Correlation-Id", "c0ffee00-1234-4abc-9def-000000000003")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange();
    }

    private int debits() {
        return WIREMOCK.findAll(postRequestedFor(urlPathMatching("/core/v1/accounts/.*/debits"))).size();
    }

    // ---------------------------------------------------------------- Fase 1: resolver destinatario

    @Test
    void celularPragbankDevuelveNombreEnmascaradoYTokenSinCuenta() {
        byte[] raw = resolve(MARIO_PHONE)
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.maskedName").isEqualTo("Mar*** Qui***")
            .jsonPath("$.bank").isEqualTo("PRAGBANK")
            .jsonPath("$.recipientToken").value(token -> assertThat(token.toString()).startsWith("rt_"))
            .jsonPath("$.expiresAt").exists()
            .jsonPath("$.accountId").doesNotExist()
            .jsonPath("$.targetAccountId").doesNotExist()
            .returnResult().getResponseBodyContent();
        String json = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).doesNotContain(MARIO_ACCOUNT).doesNotContain("Mario Quiroz").doesNotContain(MARIO_PHONE);

        Map<?, ?> resolution = resolve(MARIO_PHONE).expectBody(Map.class).returnResult().getResponseBody();
        Instant expiresAt = Instant.parse(resolution.get("expiresAt").toString());
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5));
        WIREMOCK.verify(getRequestedFor(urlEqualTo("/directory/v1/aliases/" + MARIO_PHONE)));
    }

    @Test
    void celularSinCodificarElMasTambienSeResuelve() {
        // ?phone=+519... sin codificar: el '+' llega como espacio y el handler lo restaura.
        client.get().uri("/api/v1/phone-transfers/recipients?phone=+51987654321")
            .header("X-Customer-Id", CUSTOMER)
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.maskedName").isEqualTo("Mar*** Qui***");
    }

    @Test
    void celularInvalidoResponde400PhoneInvalid() {
        for (String invalid : new String[] {"987654321", "51987654321", "+51887654321", "+5198765"}) {
            resolve(invalid)
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("PHONE_INVALID")
                .jsonPath("$.type").isEqualTo("https://api.pragbank.pe/errors/phone-invalid")
                .jsonPath("$.errors[0].field").isEqualTo("phone");
        }
        client.get().uri("/api/v1/phone-transfers/recipients").header("X-Customer-Id", CUSTOMER)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("PHONE_INVALID");
        assertThat(WIREMOCK.findAll(getRequestedFor(urlPathMatching("/directory/.*")))).isEmpty();
    }

    @Test
    void celularNoAfiliadoResponde404() {
        resolve(NOT_AFFILIATED_PHONE)
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.code").isEqualTo("RECIPIENT_NOT_FOUND")
            .jsonPath("$.title").isEqualTo("Celular no afiliado");
    }

    @Test
    void celularDeOtroBancoResponde422() {
        resolve(OTHER_BANK_PHONE)
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.code").isEqualTo("RECIPIENT_NOT_SUPPORTED")
            .jsonPath("$.detail").isEqualTo("Por ahora solo puedes pagar a celulares de clientes PragBank");
    }

    @Test
    void resolverSinClienteResponde401() {
        client.get().uri("/api/v1/phone-transfers/recipients?phone={p}", MARIO_PHONE)
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    // ---------------------------------------------------------------- Fase 2: pagar

    @Test
    void pagoExitosoQuedaCompletedConCanalPhoneYCelularEnmascarado() {
        String token = tokenFor(MARIO_PHONE);

        @SuppressWarnings("unchecked")
        Map<String, Object> created = pay(UUID.randomUUID().toString(), body(token, "45.50", "PEN"))
            .expectStatus().isCreated()
            .expectHeader().valueMatches("Location", "/api/v1/transfers/[0-9a-f-]{36}")
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(created).containsEntry("status", "COMPLETED").containsEntry("channel", "PHONE")
            .containsEntry("targetPhoneMasked", "+51 9** *** 321").containsEntry("sourceAccountId", SOURCE)
            .containsEntry("description", "Almuerzo").doesNotContainKey("targetAccountId")
            .containsEntry("amount", Map.of("value", "45.50", "currency", "PEN"));
        String transferId = created.get("transferId").toString();

        assertThat(debits()).isEqualTo(1);
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/core/v1/accounts/" + MARIO_ACCOUNT + "/credits")));
        assertThat(events.published()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(TransferEventType.TransferCompleted);
            assertThat(event.channel()).isEqualTo("PHONE");
        });
        assertThat(tokens.findById(token).block().usedByTransferId()).isEqualTo(transferId);

        // La consulta del pago tampoco expone la cuenta destino.
        client.get().uri("/api/v1/transfers/{id}", transferId).header("X-Customer-Id", CUSTOMER)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.channel").isEqualTo("PHONE")
            .jsonPath("$.targetPhoneMasked").isEqualTo("+51 9** *** 321")
            .jsonPath("$.targetAccountId").doesNotExist();
    }

    @Test
    void reintentoConLaMismaLlaveDevuelve200SinNuevoDebito() {
        String token = tokenFor(MARIO_PHONE);
        String key = UUID.randomUUID().toString();
        pay(key, body(token, "45.50", "PEN")).expectStatus().isCreated();

        pay(key, body(token, "45.50", "PEN"))
            .expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("COMPLETED");
        pay(key, body(token, "46.00", "PEN"))
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(debits()).isEqualTo(1);
    }

    @Test
    void tokenVencidoResponde422SinDebito() {
        String token = tokenFor(MARIO_PHONE);
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        pay(UUID.randomUUID().toString(), body(token, "45.50", "PEN"))
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.code").isEqualTo("RECIPIENT_TOKEN_EXPIRED")
            .jsonPath("$.detail").isEqualTo("Vuelve a buscar el celular; el token dura 5 minutos");
        assertThat(debits()).isZero();
        assertThat(events.published()).isEmpty();
    }

    @Test
    void tokenYaUsadoResponde422SinSegundoDebito() {
        String token = tokenFor(MARIO_PHONE);
        pay(UUID.randomUUID().toString(), body(token, "45.50", "PEN")).expectStatus().isCreated();

        pay(UUID.randomUUID().toString(), body(token, "45.50", "PEN"))
            .expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("RECIPIENT_TOKEN_EXPIRED");
        assertThat(debits()).isEqualTo(1);
    }

    @Test
    void tokenVencidoQueSigueEnLaTablaSeRechazaPorExpiresAt() {
        // Simula el retraso del TTL de DynamoDB: el ítem vencido todavía existe.
        Instant now = Instant.now();
        tokens.save(new RecipientToken("rt_vencido", CUSTOMER, MARIO_ACCOUNT, "+51 9** *** 321", "Mar*** Qui***",
            now.minus(Duration.ofMinutes(10)), now.minus(Duration.ofMinutes(5)), null, null)).block();

        pay(UUID.randomUUID().toString(), body("rt_vencido", "45.50", "PEN"))
            .expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("RECIPIENT_TOKEN_EXPIRED");
        assertThat(debits()).isZero();
        assertThat(tokens.findById("rt_vencido").block().isUsed()).isFalse();
    }

    @Test
    void tokenInexistenteODeOtroClienteSeRechaza() {
        pay(UUID.randomUUID().toString(), body("rt_no_existe", "45.50", "PEN"))
            .expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("RECIPIENT_TOKEN_EXPIRED");

        String token = tokenFor(MARIO_PHONE);
        client.post().uri("/api/v1/phone-transfers")
            .header("X-Customer-Id", "CUST-000456")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body(token, "45.50", "PEN"))
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("RECIPIENT_TOKEN_EXPIRED");
        assertThat(debits()).isZero();
    }

    @Test
    void cuentaOrigenDeOtroClienteResponde403SinConsumirElToken() {
        String token = tokenFor(MARIO_PHONE);
        Map<String, Object> ajena = Map.of("sourceAccountId", "191-0009999999", "recipientToken", token,
            "amount", Map.of("value", "45.50", "currency", "PEN"), "description", "Almuerzo");

        pay(UUID.randomUUID().toString(), ajena)
            .expectStatus().isForbidden()
            .expectBody().jsonPath("$.code").isEqualTo("ACCOUNT_NOT_OWNED");
        assertThat(debits()).isZero();
        assertThat(tokens.findById(token).block().isUsed()).isFalse();

        // El mismo token sigue sirviendo con la cuenta correcta.
        pay(UUID.randomUUID().toString(), body(token, "45.50", "PEN")).expectStatus().isCreated();
    }

    @Test
    void pagoAMiPropioCelularResponde422SelfTransfer() {
        String token = tokenFor(OWN_PHONE);

        pay(UUID.randomUUID().toString(), body(token, "45.50", "PEN"))
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SELF_TRANSFER")
            .jsonPath("$.title").isEqualTo("No puedes pagarte a ti mismo");
        assertThat(debits()).isZero();
        assertThat(tokens.findById(token).block().isUsed()).isFalse();
    }

    @Test
    void montoMayorAQuinientosOEnDolaresResponde422LimitePorOperacion() {
        String token = tokenFor(MARIO_PHONE);

        pay(UUID.randomUUID().toString(), body(token, "500.01", "PEN"))
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.code").isEqualTo("LIMIT_PER_TX_EXCEEDED")
            .jsonPath("$.detail").isEqualTo("El máximo por pago a celular es S/ 500.00");

        pay(UUID.randomUUID().toString(), body(token, "10.00", "USD"))
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.code").isEqualTo("LIMIT_PER_TX_EXCEEDED")
            .jsonPath("$.detail").isEqualTo("El pago a celular solo admite PEN");

        assertThat(debits()).isZero();
        // El rechazo no consume el token: el cliente puede pagar un monto válido.
        pay(UUID.randomUUID().toString(), body(token, "500.00", "PEN"))
            .expectStatus().isCreated()
            .expectBody().jsonPath("$.status").isEqualTo("COMPLETED");
        assertThat(debits()).isEqualTo(1);
    }

    @Test
    void validacionesDeEntradaDelPago() {
        String token = tokenFor(MARIO_PHONE);
        pay(UUID.randomUUID().toString(), body(token, "10.999", "PEN"))
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("AMOUNT_INVALID");

        pay(UUID.randomUUID().toString(), Map.of("sourceAccountId", SOURCE,
                "amount", Map.of("value", "10.00", "currency", "PEN")))
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].field").isEqualTo("recipientToken");

        pay(UUID.randomUUID().toString(), Map.of("sourceAccountId", SOURCE, "recipientToken", token,
                "targetAccountId", MARIO_ACCOUNT, "amount", Map.of("value", "10.00", "currency", "PEN")))
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");

        pay("no-es-uuid", body(token, "10.00", "PEN"))
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].field").isEqualTo("Idempotency-Key");

        client.post().uri("/api/v1/phone-transfers")
            .header("X-Customer-Id", CUSTOMER)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");

        assertThat(debits()).isZero();
        assertThat(tokens.findById(token).block().isUsed()).isFalse();
        assertThat(events.published()).extracting(TransferEvent::eventType).isEmpty();
    }
}
