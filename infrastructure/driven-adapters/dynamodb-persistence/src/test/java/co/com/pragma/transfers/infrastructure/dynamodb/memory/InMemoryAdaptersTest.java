package co.com.pragma.transfers.infrastructure.dynamodb.memory;

import co.com.pragma.transfers.model.idempotency.IdempotencyRecord;
import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferChannel;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class InMemoryAdaptersTest {

    private static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");

    private static Transfer transfer(String id, String customer, TransferStatus status, String amount,
                                     String currency, LocalDate day, Instant createdAt) {
        return Transfer.builder()
            .transferId(id)
            .customerId(customer)
            .sourceAccountId("191-0001234567")
            .targetAccountId("191-0007654321")
            .amount(new Money(new BigDecimal(amount), currency))
            .status(status)
            .channel(TransferChannel.ACCOUNT)
            .businessDate(day)
            .createdAt(createdAt)
            .build();
    }

    @Test
    void transfersAreStoredSortedAndSummed() {
        InMemoryTransferAdapter adapter = new InMemoryTransferAdapter();
        LocalDate day = LocalDate.of(2026, 10, 15);
        adapter.save(transfer("a", "C1", TransferStatus.COMPLETED, "100", "PEN", day, NOW)).block();
        adapter.save(transfer("b", "C1", TransferStatus.REJECTED, "50", "PEN", day, NOW.plusSeconds(1))).block();
        adapter.save(transfer("c", "C1", TransferStatus.COMPLETED, "70", "USD", day, NOW.plusSeconds(2))).block();
        adapter.save(transfer("d", "C1", TransferStatus.COMPLETED, "10", "PEN", day.minusDays(1), NOW.minusSeconds(1)))
            .block();
        adapter.save(transfer("e", "C2", TransferStatus.COMPLETED, "10", "PEN", day, NOW)).block();

        StepVerifier.create(adapter.findById("a").map(Transfer::transferId)).expectNext("a").verifyComplete();
        StepVerifier.create(adapter.findById("zz")).verifyComplete();
        StepVerifier.create(adapter.findBySourceAccount("191-0001234567").map(Transfer::transferId).take(3))
            .expectNext("c", "b")
            .expectNextCount(1)
            .verifyComplete();
        StepVerifier.create(adapter.sumDailyAmount("C1", day, "PEN"))
            .expectNext(new BigDecimal("100.00"))
            .verifyComplete();

        adapter.clear();
        StepVerifier.create(adapter.findById("a")).verifyComplete();
    }

    @Test
    void idempotencyKeepsFirstReservationUntilItExpires() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InMemoryIdempotencyAdapter adapter = new InMemoryIdempotencyAdapter(clock);
        IdempotencyRecord first = new IdempotencyRecord("k", "h1", "t1", NOW, NOW.plus(Duration.ofHours(24)));
        IdempotencyRecord second = new IdempotencyRecord("k", "h2", "t2", NOW, NOW.plus(Duration.ofHours(24)));

        StepVerifier.create(adapter.putIfAbsent(first)).verifyComplete();
        StepVerifier.create(adapter.putIfAbsent(second)).expectNext(first).verifyComplete();

        InMemoryIdempotencyAdapter later = new InMemoryIdempotencyAdapter(
            Clock.fixed(NOW.plus(Duration.ofHours(25)), ZoneOffset.UTC));
        later.putIfAbsent(first).block();
        StepVerifier.create(later.putIfAbsent(second)).verifyComplete();

        adapter.clear();
        StepVerifier.create(adapter.putIfAbsent(second)).verifyComplete();
    }
}
