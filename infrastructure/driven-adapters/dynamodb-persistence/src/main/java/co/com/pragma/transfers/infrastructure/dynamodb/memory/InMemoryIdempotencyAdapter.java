package co.com.pragma.transfers.infrastructure.dynamodb.memory;

import co.com.pragma.transfers.model.idempotency.IdempotencyRecord;
import co.com.pragma.transfers.ports.IIdempotencyGateway;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

/**
 * Tabla idempotency en memoria (local y pruebas). Replica la escritura
 * condicional de DynamoDB de forma atómica y respeta el vencimiento de 24 h.
 */
public class InMemoryIdempotencyAdapter implements IIdempotencyGateway {

    private final Map<String, IdempotencyRecord> store = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<IdempotencyRecord> putIfAbsent(IdempotencyRecord idempotencyRecord) {
        return Mono.fromCallable(() -> {
            AtomicReference<IdempotencyRecord> existing = new AtomicReference<>();
            store.compute(idempotencyRecord.idempotencyKey(), (key, current) -> {
                if (current == null || current.isExpired(clock.instant())) {
                    return idempotencyRecord;
                }
                existing.set(current);
                return current;
            });
            return existing.get();
        });
    }

    /** Limpia el almacén (pruebas). */
    public void clear() {
        store.clear();
    }
}
