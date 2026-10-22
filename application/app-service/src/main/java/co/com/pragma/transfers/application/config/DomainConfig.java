package co.com.pragma.transfers.application.config;

import co.com.pragma.transfers.model.transfer.TransferPolicy;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans de dominio que no son use cases: reloj y parámetros de las reglas. */
@Configuration
public class DomainConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TransferPolicy transferPolicy(TransfersProperties properties) {
        TransfersProperties.Limits limits = properties.getLimits();
        return new TransferPolicy(limits.getPerTransaction(), limits.getDaily(), ZoneId.of(limits.getZone()),
            limits.getIdempotencyTtl());
    }
}
