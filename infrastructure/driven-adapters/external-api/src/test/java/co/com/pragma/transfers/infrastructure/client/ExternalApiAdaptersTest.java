package co.com.pragma.transfers.infrastructure.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import co.com.pragma.transfers.infrastructure.helpers.CorrelationWebFilter;
import co.com.pragma.transfers.model.account.AccountStatus;
import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.exception.TechnicalException;
import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferChannel;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Adaptadores HTTP contra WireMock embebido en la JVM (sin Docker). */
class ExternalApiAdaptersTest {

    private static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private CoreBankingAdapter core;
    private FraudAdapter fraud;

    @BeforeAll
    static void start() {
        WIREMOCK.start();
    }

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void setUp() {
        WIREMOCK.resetAll();
        WebClient client = WebClient.builder().baseUrl(WIREMOCK.baseUrl()).build();
        core = new CoreBankingAdapter(client, calls("core", Duration.ofSeconds(2), CircuitBreaker.ofDefaults("core")));
        fraud = new FraudAdapter(client, calls("fraud", Duration.ofMillis(300), CircuitBreaker.ofDefaults("fraud")));
    }

    private static OutboundCalls calls(String name, Duration timeout, CircuitBreaker circuitBreaker) {
        Retry retry = Retry.of(name, RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(10))
            .retryExceptions(TechnicalException.class).build());
        return new OutboundCalls(name, circuitBreaker, retry, timeout);
    }

    private static Predicate<Throwable> code(ErrorCatalog error) {
        return e -> e instanceof co.com.pragma.transfers.model.exception.DomainException de
            && de.code().equals(error.code());
    }

    private static final String ACCOUNT_JSON = """
        {"accountId":"191-0001234567","customerId":"CUST-000123","holderName":"María Quispe","status":"ACTIVE",
         "currency":"PEN","availableBalance":{"value":"2500.00","currency":"PEN"}}""";

    private static final String POSTING_JSON = """
        {"postingId":"DBT-1","operationId":"TRF-t1-DEBIT","type":"DEBIT","status":"POSTED",
         "amount":{"value":"150.00","currency":"PEN"},"postedAt":"2026-10-15T15:04:05Z"}""";

    @Test
    void getAccountMapsTheCoreAccountAndPropagatesCorrelation() {
        WIREMOCK.stubFor(get(urlEqualTo("/core/v1/accounts/191-0001234567")).willReturn(okJson(ACCOUNT_JSON)));

        StepVerifier.create(core.getAccount("191-0001234567")
                .contextWrite(Context.of(CorrelationWebFilter.TRACE_ID, "corr-1")))
            .assertNext(account -> {
                assertThat(account.customerId()).isEqualTo("CUST-000123");
                assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
                assertThat(account.availableBalance().asString()).isEqualTo("2500.00");
            })
            .verifyComplete();
        WIREMOCK.verify(getRequestedFor(urlEqualTo("/core/v1/accounts/191-0001234567"))
            .withHeader("X-Correlation-Id", equalTo("corr-1")));
    }

    @Test
    void unknownAccountStatusIsTreatedAsBlockedAndMissingBalanceAsNull() {
        WIREMOCK.stubFor(get(urlEqualTo("/core/v1/accounts/191-0000000001")).willReturn(okJson(
            "{\"accountId\":\"191-0000000001\",\"customerId\":\"C\",\"status\":\"FROZEN\",\"currency\":\"PEN\"}")));
        StepVerifier.create(core.getAccount("191-0000000001"))
            .assertNext(account -> {
                assertThat(account.status()).isEqualTo(AccountStatus.BLOCKED);
                assertThat(account.availableBalance()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void missingAccountIsEmpty() {
        WIREMOCK.stubFor(get(urlEqualTo("/core/v1/accounts/191-0000000000"))
            .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"ACCOUNT_NOT_FOUND\",\"message\":\"x\"}")));
        StepVerifier.create(core.getAccount("191-0000000000")).verifyComplete();
    }

    @Test
    void readsAreRetriedOnServerErrorsAndThenFailAsUnavailable() {
        WIREMOCK.stubFor(get(urlEqualTo("/core/v1/accounts/191-0001234567")).willReturn(aResponse().withStatus(503)));
        StepVerifier.create(core.getAccount("191-0001234567"))
            .expectErrorMatches(code(ErrorCatalog.DEPENDENCY_UNAVAILABLE))
            .verify();
        WIREMOCK.verify(3, getRequestedFor(urlEqualTo("/core/v1/accounts/191-0001234567")));
    }

    @Test
    void debitSendsOperationIdAndMapsPosting() {
        WIREMOCK.stubFor(post(urlEqualTo("/core/v1/accounts/191-0001234567/debits"))
            .withRequestBody(equalToJson("{\"operationId\":\"TRF-t1-DEBIT\",\"amount\":{\"value\":\"150.00\","
                + "\"currency\":\"PEN\"},\"reference\":\"ref\"}"))
            .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                .withBody(POSTING_JSON)));
        StepVerifier.create(core.debit("191-0001234567", "TRF-t1-DEBIT", Money.parse("150", "PEN"), "ref"))
            .assertNext(posting -> assertThat(posting.postingId()).isEqualTo("DBT-1"))
            .verifyComplete();
    }

    @Test
    void writesAreNotRetried() {
        WIREMOCK.stubFor(post(urlEqualTo("/core/v1/accounts/191-0009999999/credits"))
            .willReturn(aResponse().withStatus(503).withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"CORE_UNAVAILABLE\",\"message\":\"x\"}")));
        StepVerifier.create(core.credit("191-0009999999", "op", Money.parse("1", "PEN"), "ref"))
            .expectErrorMatches(code(ErrorCatalog.DEPENDENCY_UNAVAILABLE))
            .verify();
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/core/v1/accounts/191-0009999999/credits")));
    }

    @Test
    void coreRejectionsMapToBusinessErrors() {
        WIREMOCK.stubFor(post(urlEqualTo("/core/v1/accounts/191-0001234567/debits"))
            .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"x\"}")));
        WIREMOCK.stubFor(post(urlEqualTo("/core/v1/accounts/191-0000000002/debits"))
            .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"ACCOUNT_BLOCKED\",\"message\":\"x\"}")));
        WIREMOCK.stubFor(post(urlEqualTo("/core/v1/accounts/191-0000000003/debits"))
            .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                .withBody("no es json")));

        StepVerifier.create(core.debit("191-0001234567", "op", Money.parse("1", "PEN"), "r"))
            .expectErrorMatches(code(ErrorCatalog.INSUFFICIENT_FUNDS).and(BusinessException.class::isInstance))
            .verify();
        StepVerifier.create(core.debit("191-0000000002", "op", Money.parse("1", "PEN"), "r"))
            .expectErrorMatches(code(ErrorCatalog.ACCOUNT_INVALID))
            .verify();
        StepVerifier.create(core.debit("191-0000000003", "op", Money.parse("1", "PEN"), "r"))
            .expectErrorMatches(code(ErrorCatalog.ACCOUNT_INVALID))
            .verify();
    }

    @Test
    void reversalAndOperationLookup() {
        WIREMOCK.stubFor(post(urlEqualTo("/core/v1/debits/DBT-1/reversal"))
            .withRequestBody(equalToJson("{\"operationId\":\"TRF-t1-REVERSAL\",\"reason\":\"CREDIT_FAILED\"}"))
            .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                .withBody(POSTING_JSON.replace("DBT-1", "REV-1"))));
        WIREMOCK.stubFor(get(urlEqualTo("/core/v1/operations/TRF-t1-DEBIT")).willReturn(okJson(POSTING_JSON)));
        WIREMOCK.stubFor(get(urlEqualTo("/core/v1/operations/TRF-t2-DEBIT")).willReturn(aResponse().withStatus(404)));

        StepVerifier.create(core.reverseDebit("DBT-1", "TRF-t1-REVERSAL", "CREDIT_FAILED"))
            .assertNext(posting -> assertThat(posting.postingId()).isEqualTo("REV-1"))
            .verifyComplete();
        StepVerifier.create(core.findOperation("TRF-t1-DEBIT"))
            .assertNext(posting -> assertThat(posting.type()).isEqualTo("DEBIT"))
            .verifyComplete();
        StepVerifier.create(core.findOperation("TRF-t2-DEBIT")).verifyComplete();
    }

    @Test
    void openCircuitFailsFastAsUnavailable() {
        CircuitBreaker open = CircuitBreaker.of("open", CircuitBreakerConfig.ofDefaults());
        open.transitionToForcedOpenState();
        CoreBankingAdapter guarded = new CoreBankingAdapter(WebClient.create(WIREMOCK.baseUrl()),
            calls("core", Duration.ofSeconds(1), open));
        StepVerifier.create(guarded.getAccount("191-0001234567"))
            .expectErrorMatches(code(ErrorCatalog.DEPENDENCY_UNAVAILABLE))
            .verify();
        WIREMOCK.verify(0, getRequestedFor(urlEqualTo("/core/v1/accounts/191-0001234567")));
    }

    @Test
    void defaultRegistriesIgnoreBusinessErrorsAndRetryTechnicalOnes() {
        CircuitBreakerConfig cb = ExternalApiAdaptersConfig.defaultCircuitBreakers().getDefaultConfig();
        assertThat(cb.getIgnoreExceptionPredicate().test(new BusinessException(ErrorCatalog.INSUFFICIENT_FUNDS)))
            .isTrue();
        RetryConfig retry = ExternalApiAdaptersConfig.defaultRetries().getDefaultConfig();
        assertThat(retry.getExceptionPredicate().test(new TechnicalException("X", "x", null))).isTrue();
        assertThat(retry.getExceptionPredicate().test(new BusinessException(ErrorCatalog.INSUFFICIENT_FUNDS)))
            .isFalse();
    }

    // ------------------------------------------------------------ antifraude

    private static Transfer transfer(String amount) {
        return Transfer.builder()
            .transferId("t1")
            .customerId("CUST-000123")
            .sourceAccountId("191-0001234567")
            .targetAccountId("191-0007654321")
            .amount(Money.parse(amount, "PEN"))
            .status(TransferStatus.RECEIVED)
            .channel(TransferChannel.ACCOUNT)
            .businessDate(LocalDate.of(2026, 10, 15))
            .createdAt(Instant.parse("2026-10-15T15:00:00Z"))
            .build();
    }

    @Test
    void fraudScoreFollowsTheContract() {
        WIREMOCK.stubFor(post(urlEqualTo("/fraud/v1/score"))
            .withRequestBody(equalToJson("{\"operationId\":\"TRF-t1\",\"customerId\":\"CUST-000123\","
                + "\"channel\":\"ACCOUNT\",\"sourceAccountId\":\"191-0001234567\",\"target\":\"191-0007654321\","
                + "\"amount\":{\"value\":\"9500.00\",\"currency\":\"PEN\"}}"))
            .willReturn(okJson("{\"operationId\":\"TRF-t1\",\"score\":67,\"reasons\":[\"AMOUNT_ABOVE_CUSTOMER_AVERAGE\"]}")));
        StepVerifier.create(fraud.score(transfer("9500.00")))
            .assertNext(assessment -> {
                assertThat(assessment.score()).isEqualTo(67);
                assertThat(assessment.reasons()).containsExactly("AMOUNT_ABOVE_CUSTOMER_AVERAGE");
            })
            .verifyComplete();
    }

    @Test
    void fraudTimeoutServerErrorOrMissingScoreFail() {
        WIREMOCK.stubFor(post(urlEqualTo("/fraud/v1/score")).willReturn(okJson("{\"score\":10}")
            .withFixedDelay(1000)));
        StepVerifier.create(fraud.score(transfer("1.00")))
            .expectErrorMatches(code(ErrorCatalog.DEPENDENCY_UNAVAILABLE))
            .verify();

        WIREMOCK.stubFor(post(urlEqualTo("/fraud/v1/score")).willReturn(aResponse().withStatus(503)));
        StepVerifier.create(fraud.score(transfer("1.00")))
            .expectErrorMatches(code(ErrorCatalog.DEPENDENCY_UNAVAILABLE))
            .verify();
        // Sin reintentos (2 llamadas, una por caso) y sin correlación vacía.
        WIREMOCK.verify(2, postRequestedFor(urlEqualTo("/fraud/v1/score")).withoutHeader("X-Correlation-Id"));

        WIREMOCK.stubFor(post(urlEqualTo("/fraud/v1/score")).willReturn(okJson("{\"operationId\":\"x\"}")));
        StepVerifier.create(fraud.score(transfer("1.00")))
            .expectErrorMatches(code(ErrorCatalog.DEPENDENCY_UNAVAILABLE))
            .verify();
    }
}
