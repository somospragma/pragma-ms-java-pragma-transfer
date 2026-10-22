package co.com.pragma.transfers.infrastructure.dynamodb.memory;

import co.com.pragma.transfers.model.phone.RecipientToken;
import co.com.pragma.transfers.ports.IRecipientTokenGateway;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Mono;

/**
 * Tabla recipient-tokens en memoria (local y pruebas). Replica la actualización
 * condicional de DynamoDB de forma atómica. Igual que DynamoDB con TTL, no borra
 * los tokens vencidos al instante: el vencimiento lo decide {@code expiresAt}.
 */
public class InMemoryRecipientTokenAdapter implements IRecipientTokenGateway {

    private final Map<String, RecipientToken> store = new ConcurrentHashMap<>();

    @Override
    public Mono<RecipientToken> save(RecipientToken recipientToken) {
        return Mono.fromCallable(() -> {
            store.put(recipientToken.token(), recipientToken);
            return recipientToken;
        });
    }

    @Override
    public Mono<RecipientToken> findById(String token) {
        return Mono.fromSupplier(() -> store.get(token));
    }

    @Override
    public Mono<Boolean> redeem(String token, String customerId, String transferId, Instant now) {
        return Mono.fromCallable(() -> {
            AtomicBoolean redeemed = new AtomicBoolean(false);
            store.computeIfPresent(token, (key, current) -> {
                if (!current.isRedeemableBy(customerId, transferId, now)) {
                    return current;
                }
                redeemed.set(true);
                return new RecipientToken(current.token(), current.customerId(), current.targetAccountId(),
                    current.targetPhoneMasked(), current.maskedName(), current.createdAt(), current.expiresAt(),
                    now, transferId);
            });
            return redeemed.get();
        });
    }

    /** Limpia el almacén (pruebas). */
    public void clear() {
        store.clear();
    }
}
