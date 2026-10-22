package co.com.pragma.transfers.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.com.pragma.transfers.model.account.Account;
import co.com.pragma.transfers.model.account.AccountStatus;
import co.com.pragma.transfers.model.event.TransferEvent;
import co.com.pragma.transfers.model.event.TransferEventType;
import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.fraud.FraudAssessment;
import co.com.pragma.transfers.model.fraud.FraudDecision;
import co.com.pragma.transfers.model.idempotency.IdempotencyRecord;
import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.model.transfer.PageResult;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferChannel;
import co.com.pragma.transfers.model.transfer.TransferCommand;
import co.com.pragma.transfers.model.transfer.TransferPolicy;
import co.com.pragma.transfers.model.transfer.TransferResult;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DomainModelTest {

    private static final TransferPolicy POLICY = new TransferPolicy(new BigDecimal("10000.00"),
        new BigDecimal("30000.00"), ZoneId.of("America/Lima"), Duration.ofHours(24));

    @Test
    void moneyParsesContractFormatAndRejectsInvalidAmounts() {
        assertEquals("150.00", Money.parse("150", "PEN").asString());
        assertEquals("150.50", Money.parse("150.5", "PEN").asString());
        for (String invalid : new String[] {null, "abc", "-1", "1.999", "0", "0.00", "1234567890123"}) {
            BusinessException ex = assertThrows(BusinessException.class, () -> Money.parse(invalid, "PEN"));
            assertEquals(ErrorCatalog.AMOUNT_INVALID.code(), ex.code());
            assertTrue(ex.metadata().containsKey("amount.value"));
        }
    }

    @Test
    void moneyOperations() {
        Money ten = Money.parse("10.00", "PEN");
        assertEquals("20.00", ten.plus(ten).asString());
        assertEquals("0.00", Money.zero("PEN").asString());
        assertTrue(ten.isGreaterThan(BigDecimal.ONE));
        assertFalse(ten.isGreaterThan(BigDecimal.TEN));
        assertTrue(ten.sameCurrency("PEN"));
        assertFalse(ten.sameCurrency("USD"));
    }

    @Test
    void policyAppliesPerTransactionAndDailyLimits() {
        assertFalse(POLICY.exceedsPerTransaction(Money.parse("10000.00", "PEN")));
        assertTrue(POLICY.exceedsPerTransaction(Money.parse("10000.01", "PEN")));
        assertFalse(POLICY.exceedsDaily(new BigDecimal("29000.00"), Money.parse("1000.00", "PEN")));
        assertTrue(POLICY.exceedsDaily(new BigDecimal("29000.00"), Money.parse("1000.01", "PEN")));
        // 2026-10-16T03:00Z todavía es 15 de octubre en Lima (UTC-5).
        assertEquals(LocalDate.of(2026, 10, 15), POLICY.businessDate(Instant.parse("2026-10-16T03:00:00Z")));
    }

    @Test
    void fraudDecisionFollowsRn07() {
        assertEquals(FraudDecision.APPROVE, FraudDecision.fromScore(59));
        assertEquals(FraudDecision.REVIEW, FraudDecision.fromScore(60));
        assertEquals(FraudDecision.REVIEW, FraudDecision.fromScore(79));
        assertEquals(FraudDecision.REJECT, FraudDecision.fromScore(80));
        assertEquals(FraudDecision.REVIEW, new FraudAssessment(67, null).decision());
        assertEquals(List.of(), new FraudAssessment(12, null).reasons());
    }

    @Test
    void statusesAndEvents() {
        assertFalse(TransferStatus.RECEIVED.isFinal());
        assertFalse(TransferStatus.DEBITED.isFinal());
        assertTrue(TransferStatus.COMPENSATED.isFinal());
        assertTrue(TransferStatus.PENDING_REVIEW.countsTowardDailyLimit());
        assertFalse(TransferStatus.REJECTED.countsTowardDailyLimit());
        assertFalse(TransferStatus.COMPENSATED.countsTowardDailyLimit());
        assertEquals(TransferEventType.TransferCompleted, TransferEventType.forStatus(TransferStatus.COMPLETED).orElseThrow());
        assertEquals(TransferEventType.TransferRejected, TransferEventType.forStatus(TransferStatus.REJECTED).orElseThrow());
        assertEquals(TransferEventType.TransferPendingReview,
            TransferEventType.forStatus(TransferStatus.PENDING_REVIEW).orElseThrow());
        assertEquals(TransferEventType.TransferCompensated,
            TransferEventType.forStatus(TransferStatus.COMPENSATED).orElseThrow());
        assertTrue(TransferEventType.forStatus(TransferStatus.DEBITED).isEmpty());
    }

    @Test
    void transferOperationIdsAndOwnership() {
        Transfer transfer = transfer();
        assertEquals("TRF-t1-DEBIT", transfer.debitOperationId());
        assertEquals("TRF-t1-CREDIT", transfer.creditOperationId());
        assertEquals("TRF-t1-REVERSAL", transfer.reversalOperationId());
        assertEquals("TRF-t1", transfer.fraudOperationId());
        assertTrue(transfer.belongsTo("CUST-1"));
        assertFalse(transfer.belongsTo("CUST-2"));
        assertFalse(transfer.toBuilder().customerId(null).build().belongsTo("CUST-1"));
        Instant later = Instant.parse("2026-10-15T16:00:00Z");
        Transfer completed = transfer.withStatus(TransferStatus.COMPLETED, later);
        assertEquals(TransferStatus.COMPLETED, completed.status());
        assertEquals(later, completed.updatedAt());

        TransferEvent event = TransferEvent.of("e1", TransferEventType.TransferCompleted, completed, later);
        assertEquals("ACCOUNT", event.channel());
        assertEquals("corr", event.correlationId());
        assertTrue(TransferResult.created(transfer).created());
        assertFalse(TransferResult.replayed(transfer).created());
    }

    @Test
    void fingerprintChangesWithBodyAndCustomer() {
        TransferCommand base = new TransferCommand("k", "CUST-1", "c", "191-0001234567", "191-0007654321",
            Money.parse("150", "PEN"), null);
        TransferCommand sameNormalized = new TransferCommand("k", "CUST-1", "other-corr", "191-0001234567",
            "191-0007654321", Money.parse("150.00", "PEN"), null);
        TransferCommand otherAmount = new TransferCommand("k", "CUST-1", "c", "191-0001234567", "191-0007654321",
            Money.parse("151", "PEN"), "x");
        TransferCommand otherCustomer = new TransferCommand("k", "CUST-2", "c", "191-0001234567", "191-0007654321",
            Money.parse("150", "PEN"), null);
        assertEquals(base.fingerprint(), sameNormalized.fingerprint());
        assertNotEquals(base.fingerprint(), otherAmount.fingerprint());
        assertNotEquals(base.fingerprint(), otherCustomer.fingerprint());
    }

    @Test
    void accountRules() {
        Account account = new Account("a", "CUST-1", AccountStatus.ACTIVE, "PEN", Money.parse("100", "PEN"));
        assertTrue(account.isActive());
        assertTrue(account.isOwnedBy("CUST-1"));
        assertFalse(account.isOwnedBy("CUST-2"));
        assertTrue(account.canCover(Money.parse("100", "PEN")));
        assertFalse(account.canCover(Money.parse("100.01", "PEN")));
        Account blocked = new Account("a", null, AccountStatus.BLOCKED, "PEN", null);
        assertFalse(blocked.isActive());
        assertFalse(blocked.isOwnedBy("CUST-1"));
        assertFalse(blocked.canCover(Money.parse("1", "PEN")));
    }

    @Test
    void idempotencyRecordMatchesAndExpires() {
        Instant now = Instant.parse("2026-10-15T15:00:00Z");
        IdempotencyRecord rec = new IdempotencyRecord("k", "hash", "t1", now, now.plus(Duration.ofHours(24)));
        assertTrue(rec.matches("hash"));
        assertFalse(rec.matches("other"));
        assertFalse(new IdempotencyRecord("k", null, "t1", now, null).matches("hash"));
        assertFalse(rec.isExpired(now));
        assertTrue(rec.isExpired(now.plus(Duration.ofHours(24))));
        assertFalse(new IdempotencyRecord("k", "h", "t1", now, null).isExpired(now));
    }

    @Test
    void pageResultSlicesSortedList() {
        List<Integer> items = IntStream.range(0, 45).boxed().toList();
        PageResult<Integer> first = PageResult.of(items, 0, 20);
        assertEquals(20, first.content().size());
        assertEquals(45, first.totalElements());
        assertEquals(3, first.totalPages());
        assertEquals(5, PageResult.of(items, 2, 20).content().size());
        assertEquals(0, PageResult.of(items, 9, 20).content().size());
        assertEquals(0, PageResult.of(List.<Integer>of(), 0, 20).totalPages());
    }

    @Test
    void errorCatalogLookup() {
        assertEquals(ErrorCatalog.FRAUD_REJECTED, ErrorCatalog.fromCode("FRAUD_REJECTED").orElseThrow());
        assertTrue(ErrorCatalog.fromCode("NOPE").isEmpty());
        assertEquals("idempotency-key-reused", ErrorCatalog.IDEMPOTENCY_KEY_REUSED.slug());
    }

    private static Transfer transfer() {
        Instant now = Instant.parse("2026-10-15T15:00:00Z");
        return Transfer.builder()
            .transferId("t1")
            .customerId("CUST-1")
            .sourceAccountId("191-0001234567")
            .targetAccountId("191-0007654321")
            .amount(Money.parse("150", "PEN"))
            .status(TransferStatus.RECEIVED)
            .channel(TransferChannel.ACCOUNT)
            .correlationId("corr")
            .businessDate(LocalDate.of(2026, 10, 15))
            .createdAt(now)
            .updatedAt(now)
            .build();
    }
}
