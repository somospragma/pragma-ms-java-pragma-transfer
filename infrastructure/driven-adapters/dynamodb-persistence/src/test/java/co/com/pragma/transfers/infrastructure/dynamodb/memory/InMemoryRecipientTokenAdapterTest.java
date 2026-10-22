package co.com.pragma.transfers.infrastructure.dynamodb.memory;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.pragma.transfers.model.phone.RecipientToken;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class InMemoryRecipientTokenAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");

    private static RecipientToken token(String id, Instant expiresAt) {
        return new RecipientToken(id, "CUST-000123", "191-0007654321", "+51 9** *** 321", "Mar*** Qui***", NOW,
            expiresAt, null, null);
    }

    @Test
    void tokenIsUsedOnlyOnceExceptByTheSameTransfer() {
        InMemoryRecipientTokenAdapter adapter = new InMemoryRecipientTokenAdapter();
        adapter.save(token("rt_1", NOW.plusSeconds(300))).block();

        StepVerifier.create(adapter.redeem("rt_1", "CUST-000999", "t1", NOW)).expectNext(false).verifyComplete();
        StepVerifier.create(adapter.redeem("rt_1", "CUST-000123", "t1", NOW)).expectNext(true).verifyComplete();
        StepVerifier.create(adapter.redeem("rt_1", "CUST-000123", "t2", NOW)).expectNext(false).verifyComplete();
        // Reintento de la misma transferencia.
        StepVerifier.create(adapter.redeem("rt_1", "CUST-000123", "t1", NOW.plusSeconds(600)))
            .expectNext(true).verifyComplete();
        StepVerifier.create(adapter.findById("rt_1"))
            .assertNext(stored -> {
                assertThat(stored.usedAt()).isEqualTo(NOW.plusSeconds(600));
                assertThat(stored.usedByTransferId()).isEqualTo("t1");
            })
            .verifyComplete();
    }

    @Test
    void expiredTokenStaysStoredButCannotBeUsed() {
        InMemoryRecipientTokenAdapter adapter = new InMemoryRecipientTokenAdapter();
        adapter.save(token("rt_old", NOW.minusSeconds(1))).block();

        StepVerifier.create(adapter.findById("rt_old")).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.redeem("rt_old", "CUST-000123", "t1", NOW)).expectNext(false).verifyComplete();
        StepVerifier.create(adapter.redeem("rt_missing", "CUST-000123", "t1", NOW)).expectNext(false).verifyComplete();

        adapter.clear();
        StepVerifier.create(adapter.findById("rt_old")).verifyComplete();
    }
}
