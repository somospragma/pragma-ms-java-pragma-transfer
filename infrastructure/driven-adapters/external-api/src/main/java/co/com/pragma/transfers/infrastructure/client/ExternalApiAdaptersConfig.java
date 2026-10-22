package co.com.pragma.transfers.infrastructure.client;

import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.TechnicalException;
import co.com.pragma.transfers.ports.IAliasDirectoryGateway;
import co.com.pragma.transfers.ports.ICoreBankingGateway;
import co.com.pragma.transfers.ports.IFraudGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Registra los adaptadores del core bancario, antifraude y directorio de alias con su resiliencia
 * (resilience4j). Si el starter de resilience4j publica sus registros (config
 * de application.yml) se usan esos; si no, se crean con valores equivalentes.
 * En ambos casos: el circuit breaker ignora los rechazos de negocio y el retry
 * solo reintenta fallas técnicas.
 */
@Configuration
public class ExternalApiAdaptersConfig {

    static final String CORE = "coreBanking";
    static final String FRAUD = "fraud";
    static final String DIRECTORY = "directory";

    @Bean
    public ICoreBankingGateway coreBankingGateway(
            @Qualifier("coreBankingWebClient") WebClient coreBankingWebClient,
            ObjectProvider<CircuitBreakerRegistry> circuitBreakers,
            ObjectProvider<RetryRegistry> retries,
            @Value("${core-banking.timeout:2s}") Duration timeout) {
        OutboundCalls calls = new OutboundCalls("core bancario",
            circuitBreakers.getIfAvailable(ExternalApiAdaptersConfig::defaultCircuitBreakers).circuitBreaker(CORE),
            retries.getIfAvailable(ExternalApiAdaptersConfig::defaultRetries).retry(CORE),
            timeout);
        return new CoreBankingAdapter(coreBankingWebClient, calls);
    }

    @Bean
    public IFraudGateway fraudGateway(
            @Qualifier("fraudWebClient") WebClient fraudWebClient,
            ObjectProvider<CircuitBreakerRegistry> circuitBreakers,
            ObjectProvider<RetryRegistry> retries,
            @Value("${fraud.timeout:1s}") Duration timeout) {
        OutboundCalls calls = new OutboundCalls("antifraude",
            circuitBreakers.getIfAvailable(ExternalApiAdaptersConfig::defaultCircuitBreakers).circuitBreaker(FRAUD),
            retries.getIfAvailable(ExternalApiAdaptersConfig::defaultRetries).retry(FRAUD),
            timeout);
        return new FraudAdapter(fraudWebClient, calls);
    }

    @Bean
    public IAliasDirectoryGateway aliasDirectoryGateway(
            @Qualifier("directoryWebClient") WebClient directoryWebClient,
            ObjectProvider<CircuitBreakerRegistry> circuitBreakers,
            ObjectProvider<RetryRegistry> retries,
            @Value("${directory.timeout:2s}") Duration timeout) {
        OutboundCalls calls = new OutboundCalls("directorio de alias",
            circuitBreakers.getIfAvailable(ExternalApiAdaptersConfig::defaultCircuitBreakers).circuitBreaker(DIRECTORY),
            retries.getIfAvailable(ExternalApiAdaptersConfig::defaultRetries).retry(DIRECTORY),
            timeout);
        return new AliasDirectoryAdapter(directoryWebClient, calls);
    }

    static CircuitBreakerRegistry defaultCircuitBreakers() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .permittedNumberOfCallsInHalfOpenState(3)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .failureRateThreshold(50)
            .ignoreExceptions(BusinessException.class)
            .build());
    }

    static RetryRegistry defaultRetries() {
        return RetryRegistry.of(RetryConfig.custom()
            .maxAttempts(3)
            .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(200), 2, 0.5))
            .retryExceptions(TechnicalException.class)
            .build());
    }
}
