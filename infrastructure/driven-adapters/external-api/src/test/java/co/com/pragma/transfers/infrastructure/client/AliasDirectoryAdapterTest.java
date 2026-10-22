package co.com.pragma.transfers.infrastructure.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import co.com.pragma.transfers.infrastructure.helpers.CorrelationWebFilter;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.exception.TechnicalException;
import co.com.pragma.transfers.model.phone.AliasBank;
import co.com.pragma.transfers.model.phone.PhoneNumber;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Directorio de alias contra WireMock embebido (sin Docker). */
class AliasDirectoryAdapterTest {

    private static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final PhoneNumber PHONE = PhoneNumber.parse("+51987654321");
    private static final String PATH = "/directory/v1/aliases/+51987654321";

    private AliasDirectoryAdapter directory;

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
        Retry retry = Retry.of("directory", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(10))
            .retryExceptions(TechnicalException.class).build());
        directory = new AliasDirectoryAdapter(client, new OutboundCalls("directorio", CircuitBreaker.ofDefaults("d"),
            retry, Duration.ofMillis(500)));
    }

    @Test
    void pragbankAliasKeepsThePlusLiteralInThePathAndPropagatesCorrelation() {
        WIREMOCK.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
            {"phone":"+51987654321","bank":"PRAGBANK","accountId":"191-0007654321",
             "holderName":"Mario Quiroz","status":"ACTIVE"}""")));

        StepVerifier.create(directory.resolve(PHONE).contextWrite(Context.of(CorrelationWebFilter.TRACE_ID, "c-1")))
            .assertNext(alias -> {
                assertThat(alias.bank()).isEqualTo(AliasBank.PRAGBANK);
                assertThat(alias.accountId()).isEqualTo("191-0007654321");
                assertThat(alias.maskedName()).isEqualTo("Mar*** Qui***");
                assertThat(alias.active()).isTrue();
                assertThat(alias.isPayable()).isTrue();
            })
            .verifyComplete();
        WIREMOCK.verify(getRequestedFor(urlEqualTo(PATH)).withHeader("X-Correlation-Id", equalTo("c-1")));
    }

    @Test
    void otherBankAndSuspendedAliasesAreMapped() {
        WIREMOCK.stubFor(get(urlEqualTo(PATH))
            .willReturn(okJson("{\"phone\":\"+51987654321\",\"bank\":\"OTHER\",\"status\":\"SUSPENDED\"}")));
        StepVerifier.create(directory.resolve(PHONE))
            .assertNext(alias -> {
                assertThat(alias.bank()).isEqualTo(AliasBank.OTHER);
                assertThat(alias.active()).isFalse();
                assertThat(alias.isPayable()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void notAffiliatedIsEmpty() {
        WIREMOCK.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404)));
        StepVerifier.create(directory.resolve(PHONE)).verifyComplete();
    }

    @Test
    void serverErrorIsRetriedAndThenUnavailable() {
        WIREMOCK.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
        StepVerifier.create(directory.resolve(PHONE))
            .verifyErrorMatches(e -> e instanceof TechnicalException te
                && te.code().equals(ErrorCatalog.DEPENDENCY_UNAVAILABLE.code()));
        WIREMOCK.verify(3, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void slowDirectoryTimesOut() {
        WIREMOCK.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("{}").withFixedDelay(1500)));
        StepVerifier.create(directory.resolve(PHONE))
            .verifyErrorMatches(e -> e instanceof TechnicalException);
    }
}
