package co.com.pragma.transfers.infrastructure.client;

import co.com.pragma.transfers.infrastructure.helpers.CorrelationWebFilter;
import co.com.pragma.transfers.model.exception.DomainException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.exception.TechnicalException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

/**
 * Envoltura común de las llamadas salientes: propaga X-Correlation-Id desde el
 * Reactor Context, aplica timeout, traduce fallas técnicas a
 * DEPENDENCY_UNAVAILABLE y protege con circuit breaker. El retry solo se aplica
 * a lecturas (los movimientos se protegen con su operationId, no con reintentos ciegos).
 */
final class OutboundCalls {

    static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final String dependency;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Duration timeout;

    OutboundCalls(String dependency, CircuitBreaker circuitBreaker, Retry retry, Duration timeout) {
        this.dependency = dependency;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.timeout = timeout;
    }

    /** Agrega X-Correlation-Id solo si la petición entrante trae uno. */
    static Consumer<HttpHeaders> correlation(String correlationId) {
        return headers -> {
            if (correlationId != null && !correlationId.isBlank()) {
                headers.set(CORRELATION_HEADER, correlationId);
            }
        };
    }

    <T> Mono<T> read(Function<String, Mono<T>> call) {
        return protect(guarded(call).transformDeferred(RetryOperator.of(retry)));
    }

    <T> Mono<T> write(Function<String, Mono<T>> call) {
        return protect(guarded(call));
    }

    private <T> Mono<T> guarded(Function<String, Mono<T>> call) {
        return Mono.deferContextual(ctx -> call.apply(ctx.getOrDefault(CorrelationWebFilter.TRACE_ID, "")))
            .timeout(timeout)
            .onErrorMap(error -> !(error instanceof DomainException), this::unavailable)
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    private <T> Mono<T> protect(Mono<T> mono) {
        return mono.onErrorMap(CallNotPermittedException.class, this::unavailable);
    }

    TechnicalException unavailable(Throwable cause) {
        return new TechnicalException(ErrorCatalog.DEPENDENCY_UNAVAILABLE, dependency + " no disponible", cause);
    }

    TechnicalException unavailable(int status) {
        return new TechnicalException(ErrorCatalog.DEPENDENCY_UNAVAILABLE,
            dependency + " respondió HTTP " + status, null);
    }
}
