package co.com.pragma.transfers.infrastructure.dynamodb.memory;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Persistencia en memoria cuando dynamodb.enabled=false (local y pruebas sin AWS). */
@Configuration
@ConditionalOnProperty(prefix = "dynamodb", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryPersistenceConfig {

    @Bean
    public InMemoryTransferAdapter inMemoryTransferAdapter() {
        return new InMemoryTransferAdapter();
    }

    @Bean
    public InMemoryIdempotencyAdapter inMemoryIdempotencyAdapter(Clock clock) {
        return new InMemoryIdempotencyAdapter(clock);
    }
}
