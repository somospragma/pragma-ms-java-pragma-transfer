package co.com.pragma.transfers.infrastructure.dynamodb.memory;

import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.ports.ITransferGateway;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tabla transfers en memoria. Solo para local y pruebas (dynamodb.enabled=false):
 * permite que el servicio arranque sin AWS. No es durable ni compartida entre réplicas.
 */
public class InMemoryTransferAdapter implements ITransferGateway {

    private final Map<String, Transfer> store = new ConcurrentHashMap<>();

    @Override
    public Mono<Transfer> save(Transfer transfer) {
        return Mono.fromCallable(() -> {
            store.put(transfer.transferId(), transfer);
            return transfer;
        });
    }

    @Override
    public Mono<Transfer> findById(String transferId) {
        return Mono.justOrEmpty(store.get(transferId));
    }

    @Override
    public Flux<Transfer> findBySourceAccount(String sourceAccountId) {
        return Flux.defer(() -> Flux.fromStream(store.values().stream()
            .filter(t -> sourceAccountId.equals(t.sourceAccountId()))
            .sorted(Comparator.comparing(Transfer::createdAt).reversed())));
    }

    @Override
    public Mono<BigDecimal> sumDailyAmount(String customerId, LocalDate businessDate, String currency) {
        return Mono.fromCallable(() -> store.values().stream()
            .filter(t -> customerId.equals(t.customerId()))
            .filter(t -> businessDate.equals(t.businessDate()))
            .filter(t -> t.amount().sameCurrency(currency))
            .filter(t -> t.status().countsTowardDailyLimit())
            .map(t -> t.amount().value())
            .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Limpia el almacén (pruebas). */
    public void clear() {
        store.clear();
    }
}
