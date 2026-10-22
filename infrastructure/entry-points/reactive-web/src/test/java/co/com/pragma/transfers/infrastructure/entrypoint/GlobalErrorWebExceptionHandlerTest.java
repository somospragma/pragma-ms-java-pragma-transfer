package co.com.pragma.transfers.infrastructure.entrypoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import co.com.pragma.transfers.infrastructure.helpers.CorrelationWebFilter;
import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.exception.TechnicalException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.test.StepVerifier;

class GlobalErrorWebExceptionHandlerTest {

    private final GlobalErrorWebExceptionHandler handler = new GlobalErrorWebExceptionHandler();

    private ServerWebExchange exchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/transfers").build());
        exchange.getAttributes().put(CorrelationWebFilter.TRACE_ID, "corr-1");
        return exchange;
    }

    private String bodyOf(ServerWebExchange ex) {
        return ((MockServerHttpResponse) ex.getResponse()).getBodyAsString().block();
    }

    private ServerWebExchange handle(Throwable error) {
        ServerWebExchange ex = exchange();
        StepVerifier.create(handler.handle(ex, error)).verifyComplete();
        return ex;
    }

    /** Cada code de negocio del contrato sale con su status HTTP. */
    @ParameterizedTest
    @EnumSource(value = ErrorCatalog.class, names = {"VALIDATION_ERROR", "AMOUNT_INVALID", "UNAUTHORIZED",
        "ACCOUNT_NOT_OWNED", "TRANSFER_NOT_FOUND", "IDEMPOTENCY_KEY_REUSED", "ACCOUNT_INVALID", "CURRENCY_MISMATCH",
        "LIMIT_PER_TX_EXCEEDED", "DAILY_LIMIT_EXCEEDED", "INSUFFICIENT_FUNDS", "FRAUD_REJECTED"})
    void businessCodesMapToContractStatus(ErrorCatalog error) {
        ServerWebExchange ex = handle(new BusinessException(error));
        assertEquals(error.httpStatus(), ex.getResponse().getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, ex.getResponse().getHeaders().getContentType());
        assertThat(bodyOf(ex))
            .contains("\"code\":\"" + error.code() + "\"")
            .contains("https://api.pragbank.pe/errors/" + error.slug())
            .contains("\"correlationId\":\"corr-1\"");
    }

    @Test
    void contractStatusesAreTheExpectedOnes() {
        assertEquals(400, ErrorCatalog.AMOUNT_INVALID.httpStatus());
        assertEquals(403, ErrorCatalog.ACCOUNT_NOT_OWNED.httpStatus());
        assertEquals(404, ErrorCatalog.TRANSFER_NOT_FOUND.httpStatus());
        assertEquals(409, ErrorCatalog.IDEMPOTENCY_KEY_REUSED.httpStatus());
        assertEquals(422, ErrorCatalog.INSUFFICIENT_FUNDS.httpStatus());
        assertEquals(503, ErrorCatalog.DEPENDENCY_UNAVAILABLE.httpStatus());
    }

    @Test
    void metadataIsRenderedAsErrorsArray() {
        ServerWebExchange ex = handle(new BusinessException(ErrorCatalog.AMOUNT_INVALID, "monto",
            Map.of("amount.value", "debe ser mayor a 0")));
        assertThat(bodyOf(ex)).contains("\"errors\":[{").contains("\"field\":\"amount.value\"");
    }

    @Test
    void unknownBusinessCodeMaps422() {
        ServerWebExchange ex = handle(new BusinessException("RULE_BROKEN", "rule"));
        assertEquals(422, ex.getResponse().getStatusCode().value());
        assertThat(bodyOf(ex)).contains("business-rule");
    }

    @Test
    void dependencyUnavailableMaps503WithRetryAfterAndNoInternals() {
        ServerWebExchange ex = handle(new TechnicalException(ErrorCatalog.DEPENDENCY_UNAVAILABLE,
            "core at 10.0.0.1 down", new RuntimeException()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getResponse().getStatusCode());
        assertEquals("5", ex.getResponse().getHeaders().getFirst("Retry-After"));
        assertThat(bodyOf(ex)).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("10.0.0.1");
    }

    @Test
    void technicalErrorMaps500WithoutLeakingInternals() {
        ServerWebExchange ex = handle(new TechnicalException("DB_DOWN", "db down at host:5432",
            new RuntimeException()));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getResponse().getStatusCode());
        assertThat(bodyOf(ex)).contains("UNEXPECTED_ERROR").doesNotContain("db down at host");
    }

    @Test
    void unhandledErrorMaps500() {
        ServerWebExchange ex = handle(new RuntimeException("boom"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getResponse().getStatusCode());
        assertThat(bodyOf(ex)).doesNotContain("boom");
    }

    @Test
    void illegalArgumentMaps400() {
        ServerWebExchange ex = handle(new IllegalArgumentException("bad"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getResponse().getStatusCode());
        assertThat(bodyOf(ex)).contains("VALIDATION_ERROR");
    }

    @Test
    void unreadableBodyMaps400WithoutParserDetail() {
        ServerWebExchange ex = handle(new ServerWebInputException("Unrecognized field \"extra\""));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getResponse().getStatusCode());
        assertThat(bodyOf(ex)).contains("VALIDATION_ERROR").doesNotContain("Unrecognized");
    }

    @Test
    void responseStatusExceptionsMapByStatus() {
        assertThat(bodyOf(handle(new ResponseStatusException(HttpStatus.NOT_FOUND)))).contains("ROUTE_NOT_FOUND");
        ServerWebExchange conflict = handle(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "no"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, conflict.getResponse().getStatusCode());
        assertThat(bodyOf(conflict)).contains("VALIDATION_ERROR");
        ServerWebExchange server = handle(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "secret"));
        assertEquals(HttpStatus.BAD_GATEWAY, server.getResponse().getStatusCode());
        assertThat(bodyOf(server)).contains("UNEXPECTED_ERROR").doesNotContain("secret");
    }

    @Test
    void bindExceptionMapsFieldErrors() throws NoSuchMethodException {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "request");
        result.addError(new org.springframework.validation.FieldError("request", "amount", "requerido"));
        MethodParameter parameter = new MethodParameter(
            GlobalErrorWebExceptionHandlerTest.class.getDeclaredMethod("bodyOf", ServerWebExchange.class), 0);
        ServerWebExchange ex = handle(new WebExchangeBindException(parameter, result));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getResponse().getStatusCode());
        assertThat(bodyOf(ex)).contains("\"field\":\"amount\"");
    }

    @Test
    void problemWithoutCorrelationOmitsIt() {
        ServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());
        StepVerifier.create(handler.handle(ex, new BusinessException(ErrorCatalog.TRANSFER_NOT_FOUND)))
            .verifyComplete();
        assertThat(bodyOf(ex)).doesNotContain("correlationId");
    }
}
