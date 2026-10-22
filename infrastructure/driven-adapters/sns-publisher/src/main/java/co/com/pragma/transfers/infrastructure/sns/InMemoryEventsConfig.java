package co.com.pragma.transfers.infrastructure.sns;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Publicador en memoria cuando sns.enabled=false (local y pruebas sin AWS). */
@Configuration
@ConditionalOnProperty(prefix = "sns", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryEventsConfig {

    @Bean
    public InMemoryTransferEventPublisher inMemoryTransferEventPublisher() {
        return new InMemoryTransferEventPublisher();
    }
}
