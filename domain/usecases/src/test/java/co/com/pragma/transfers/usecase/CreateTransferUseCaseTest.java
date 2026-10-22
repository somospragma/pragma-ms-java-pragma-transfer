package co.com.pragma.transfers.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.com.pragma.transfers.model.account.Account;
import co.com.pragma.transfers.model.account.AccountStatus;
import co.com.pragma.transfers.model.account.Posting;
import co.com.pragma.transfers.model.event.TransferEvent;
import co.com.pragma.transfers.model.event.TransferEventType;
import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.exception.TechnicalException;
import co.com.pragma.transfers.model.fraud.FraudAssessment;
import co.com.pragma.transfers.model.idempotency.IdempotencyRecord;
import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferChannel;
import co.com.pragma.transfers.model.transfer.TransferCommand;
import co.com.pragma.transfers.model.transfer.TransferPolicy;
import co.com.pragma.transfers.model.transfer.TransferResult;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import co.com.pragma.transfers.ports.ICoreBankingGateway;
import co.com.pragma.transfers.ports.IFraudGateway;
import co.com.pragma.transfers.ports.IIdempotencyGateway;
import co.com.pragma.transfers.ports.ITransferEventPublisher;
import co.com.pragma.transfers.ports.ITransferGateway;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class CreateTransferUseCaseTest {

    private static final String CUSTOMER = "CUST-000123";
    private static final String SOURCE = "191-0001234567";
    private static final String TARGET = "191-0007654321";
    private static final Instant NOW = Instant.parse("2026-10-15T15:04:05Z");

    @Mock
    private ITransferGateway transfers;
    @Mock
    private IIdempotencyGateway idempotency;
    @Mock
    private ICoreBankingGateway core;
    @Mock
    private IFraudGateway fraud;
    @Mock
    private ITransferEventPublisher events;

    private CreateTransferUseCase useCase;

    @BeforeEach
    void setUp() {
        TransferPolicy policy = new TransferPolicy(new BigDecimal("10000.00"), new BigDecimal("30000.00"),
            ZoneId.of("America/Lima"), Duration.ofHours(24));
        useCase = new CreateTransferUseCase(transfers, idempotency, core, fraud, events, policy,
            Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(idempotency.putIfAbsent(any())).thenReturn(Mono.empty());
        lenient().when(transfers.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(transfers.sumDailyAmount(anyString(), any(), anyString())).thenReturn(Mono.just(BigDecimal.ZERO));
        lenient().when(events.publish(any())).thenReturn(Mono.empty());
        lenient().when(core.getAccount(SOURCE)).thenReturn(Mono.just(account(SOURCE, CUSTOMER, "2500.00", "PEN")));
        lenient().when(core.getAccount(TARGET)).thenReturn(Mono.just(account(TARGET, "CUST-000456", "800.00", "PEN")));
        lenient().when(fraud.score(any())).thenReturn(Mono.just(new FraudAssessment(12, List.of())));
        lenient().when(core.debit(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just(new Posting("DBT-1", "op", "DEBIT", "POSTED")));
        lenient().when(core.credit(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just(new Posting("CRD-1", "op", "CREDIT", "POSTED")));
        lenient().when(core.reverseDebit(anyString(), anyString(), anyString()))
            .thenReturn(Mono.just(new Posting("REV-1", "op", "REVERSAL", "POSTED")));
    }

    private static Account account(String id, String customer, String balance, String currency) {
        return new Account(id, customer, AccountStatus.ACTIVE, currency, new Money(new BigDecimal(balance), currency));
    }

    private static TransferCommand command(String amount) {
        return new TransferCommand("3b1f6c2a-9d4e-4f7a-8b21-5c6d7e8f9a01", CUSTOMER, "corr-1", SOURCE, TARGET,
            Money.parse(amount, "PEN"), "Pago alquiler octubre");
    }

    private static Transfer stored(String transferId, TransferStatus status, String amount) {
        return Transfer.builder()
            .transferId(transferId)
            .customerId(CUSTOMER)
            .sourceAccountId(SOURCE)
            .targetAccountId(TARGET)
            .amount(Money.parse(amount, "PEN"))
            .status(status)
            .channel(TransferChannel.ACCOUNT)
            .debitPostingId(status == TransferStatus.DEBITED ? "DBT-9" : null)
            .businessDate(LocalDate.of(2026, 10, 15))
            .createdAt(NOW)
            .updatedAt(NOW)
            .build();
    }

    private static Predicate<Throwable> code(ErrorCatalog error) {
        return e -> e instanceof BusinessException be && be.code().equals(error.code());
    }

    private void givenExistingKey(TransferCommand command, String transferId) {
        when(idempotency.putIfAbsent(any())).thenReturn(Mono.just(new IdempotencyRecord(command.idempotencyKey(),
            command.fingerprint(), transferId, NOW, NOW.plus(Duration.ofHours(24)))));
    }

    private TransferEventType lastEvent() {
        ArgumentCaptor<TransferEvent> captor = ArgumentCaptor.forClass(TransferEvent.class);
        verify(events, atLeastOnce()).publish(captor.capture());
        return captor.getValue().eventType();
    }

    // ------------------------------------------------------------ camino feliz

    @Test
    void completesTransferAndPublishesEvent() {
        StepVerifier.create(useCase.execute(command("150.00")))
            .assertNext(result -> {
                assertThat(result.created()).isTrue();
                Transfer t = result.transfer();
                assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
                assertThat(t.debitPostingId()).isEqualTo("DBT-1");
                assertThat(t.creditPostingId()).isEqualTo("CRD-1");
                assertThat(t.fraudScore()).isEqualTo(12);
                assertThat(t.businessDate()).isEqualTo(LocalDate.of(2026, 10, 15));
                assertThat(t.correlationId()).isEqualTo("corr-1");
            })
            .verifyComplete();
        verify(core).debit(eq(SOURCE), any(), any(), any());
        assertThat(lastEvent()).isEqualTo(TransferEventType.TransferCompleted);
    }

    @Test
    void eventFailureDoesNotBreakTheTransfer() {
        when(events.publish(any())).thenReturn(Mono.error(new IllegalStateException("sns down")));
        StepVerifier.create(useCase.execute(command("150.00")).map(r -> r.transfer().status()))
            .expectNext(TransferStatus.COMPLETED)
            .verifyComplete();
    }

    // ---------------------------------------------------------------- RN-08

    @Test
    void replaysFinalTransferWithoutMovingMoney() {
        TransferCommand command = command("150.00");
        givenExistingKey(command, "t-1");
        when(transfers.findById("t-1")).thenReturn(Mono.just(stored("t-1", TransferStatus.COMPLETED, "150.00")));

        StepVerifier.create(useCase.execute(command))
            .assertNext(result -> {
                assertThat(result.created()).isFalse();
                assertThat(result.transfer().transferId()).isEqualTo("t-1");
            })
            .verifyComplete();
        verify(core, never()).debit(anyString(), anyString(), any(), anyString());
    }

    @Test
    void sameKeyWithDifferentBodyIsConflict() {
        TransferCommand command = command("150.00");
        givenExistingKey(command, "t-1");
        StepVerifier.create(useCase.execute(command("151.00")))
            .expectErrorMatches(code(ErrorCatalog.IDEMPOTENCY_KEY_REUSED))
            .verify();
    }

    @Test
    void replayOfRejectedTransferReturnsTheSameError() {
        TransferCommand command = command("150.00");
        givenExistingKey(command, "t-1");
        when(transfers.findById("t-1")).thenReturn(Mono.just(stored("t-1", TransferStatus.REJECTED, "150.00")
            .toBuilder().failureCode("INSUFFICIENT_FUNDS").build()));
        StepVerifier.create(useCase.execute(command))
            .expectErrorMatches(code(ErrorCatalog.INSUFFICIENT_FUNDS))
            .verify();
    }

    @Test
    void replayOfRejectedTransferWithUnknownCodeKeepsTheCode() {
        TransferCommand command = command("150.00");
        givenExistingKey(command, "t-1");
        when(transfers.findById("t-1")).thenReturn(Mono.just(stored("t-1", TransferStatus.REJECTED, "150.00")
            .toBuilder().failureCode("ACCOUNT_BLOCKED").build()));
        StepVerifier.create(useCase.execute(command))
            .expectErrorMatches(e -> e instanceof BusinessException be && be.code().equals("ACCOUNT_BLOCKED"))
            .verify();
    }

    @Test
    void reservedKeyWithoutTransferIsProcessedWithTheSameId() {
        TransferCommand command = command("150.00");
        givenExistingKey(command, "t-1");
        when(transfers.findById("t-1")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(command))
            .assertNext(result -> {
                assertThat(result.created()).isTrue();
                assertThat(result.transfer().transferId()).isEqualTo("t-1");
            })
            .verifyComplete();
        verify(core).debit(SOURCE, "TRF-t-1-DEBIT", Money.parse("150.00", "PEN"), "Transferencia t-1");
    }

    @Test
    void resumesDebitedTransferFromTheCredit() {
        TransferCommand command = command("150.00");
        givenExistingKey(command, "t-1");
        when(transfers.findById("t-1")).thenReturn(Mono.just(stored("t-1", TransferStatus.DEBITED, "150.00")));
        StepVerifier.create(useCase.execute(command).map(r -> r.transfer().status()))
            .expectNext(TransferStatus.COMPLETED)
            .verifyComplete();
        verify(core, never()).debit(anyString(), anyString(), any(), anyString());
        verify(core).credit(TARGET, "TRF-t-1-CREDIT", Money.parse("150.00", "PEN"), "Transferencia t-1");
    }

    @Test
    void resumesReceivedTransferWhoseDebitWasApplied() {
        TransferCommand command = command("150.00");
        givenExistingKey(command, "t-1");
        when(transfers.findById("t-1")).thenReturn(Mono.just(stored("t-1", TransferStatus.RECEIVED, "150.00")));
        when(core.findOperation("TRF-t-1-DEBIT")).thenReturn(Mono.just(new Posting("DBT-7", "TRF-t-1-DEBIT",
            "DEBIT", "POSTED")));
        StepVerifier.create(useCase.execute(command))
            .assertNext(result -> {
                assertThat(result.transfer().status()).isEqualTo(TransferStatus.COMPLETED);
                assertThat(result.transfer().debitPostingId()).isEqualTo("DBT-7");
            })
            .verifyComplete();
        verify(core, never()).debit(anyString(), anyString(), any(), anyString());
    }

    @Test
    void resumesReceivedTransferWithoutDebitReevaluatingWithoutCountingItTwice() {
        TransferCommand command = command("1000.00");
        givenExistingKey(command, "t-1");
        when(transfers.findById("t-1")).thenReturn(Mono.just(stored("t-1", TransferStatus.RECEIVED, "1000.00")));
        when(core.findOperation("TRF-t-1-DEBIT")).thenReturn(Mono.empty());
        // El acumulado ya incluye esta transferencia (RECEIVED): 30000 exactos no exceden.
        when(transfers.sumDailyAmount(anyString(), any(), anyString())).thenReturn(Mono.just(new BigDecimal("30000.00")));
        when(core.getAccount(SOURCE)).thenReturn(Mono.just(account(SOURCE, CUSTOMER, "5000.00", "PEN")));
        StepVerifier.create(useCase.execute(command).map(r -> r.transfer().status()))
            .expectNext(TransferStatus.COMPLETED)
            .verifyComplete();
        verify(core).debit(eq(SOURCE), eq("TRF-t-1-DEBIT"), any(), anyString());
    }

    // ------------------------------------------------------------- reglas

    @Test
    void sourceAccountOfAnotherCustomerIsForbiddenWithoutPersisting() {
        when(core.getAccount(SOURCE)).thenReturn(Mono.just(account(SOURCE, "CUST-999", "2500.00", "PEN")));
        StepVerifier.create(useCase.execute(command("150.00")))
            .expectErrorMatches(code(ErrorCatalog.ACCOUNT_NOT_OWNED))
            .verify();
        verify(transfers, never()).save(any());
        verify(events, never()).publish(any());
    }

    @Test
    void missingSourceAccountIsForbidden() {
        when(core.getAccount(SOURCE)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(command("150.00")))
            .expectErrorMatches(code(ErrorCatalog.ACCOUNT_NOT_OWNED))
            .verify();
    }

    @Test
    void perTransactionLimitRejectsAndPublishes() {
        StepVerifier.create(useCase.execute(command("10000.01")))
            .expectErrorMatches(code(ErrorCatalog.LIMIT_PER_TX_EXCEEDED))
            .verify();
        ArgumentCaptor<Transfer> saved = ArgumentCaptor.forClass(Transfer.class);
        verify(transfers).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(saved.getValue().failureCode()).isEqualTo("LIMIT_PER_TX_EXCEEDED");
        assertThat(lastEvent()).isEqualTo(TransferEventType.TransferRejected);
        verify(fraud, never()).score(any());
    }

    @Test
    void dailyLimitRejects() {
        when(transfers.sumDailyAmount(eq(CUSTOMER), eq(LocalDate.of(2026, 10, 15)), eq("PEN")))
            .thenReturn(Mono.just(new BigDecimal("29900.00")));
        StepVerifier.create(useCase.execute(command("150.00")))
            .expectErrorMatches(code(ErrorCatalog.DAILY_LIMIT_EXCEEDED))
            .verify();
    }

    @Test
    void emptyDailySumCountsAsZero() {
        when(transfers.sumDailyAmount(anyString(), any(), anyString())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(command("150.00")).map(r -> r.transfer().status()))
            .expectNext(TransferStatus.COMPLETED)
            .verifyComplete();
    }

    @Test
    void missingTargetAccountIsInvalid() {
        when(core.getAccount(TARGET)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(command("150.00")))
            .expectErrorMatches(code(ErrorCatalog.ACCOUNT_INVALID))
            .verify();
    }

    @Test
    void inactiveAccountIsInvalid() {
        when(core.getAccount(TARGET)).thenReturn(Mono.just(new Account(TARGET, "CUST-000456", AccountStatus.CLOSED,
            "PEN", Money.parse("1", "PEN"))));
        StepVerifier.create(useCase.execute(command("150.00")))
            .expectErrorMatches(code(ErrorCatalog.ACCOUNT_INVALID))
            .verify();
    }

    @Test
    void blockedSourceAccountIsInvalid() {
        when(core.getAccount(SOURCE)).thenReturn(Mono.just(new Account(SOURCE, CUSTOMER, AccountStatus.BLOCKED,
            "PEN", Money.parse("2500", "PEN"))));
        StepVerifier.create(useCase.execute(command("150.00")))
            .expectErrorMatches(code(ErrorCatalog.ACCOUNT_INVALID))
            .verify();
    }

    @Test
    void sameSourceAndTargetIsInvalid() {
        TransferCommand self = new TransferCommand("k", CUSTOMER, "c", SOURCE, SOURCE, Money.parse("1", "PEN"), null);
        StepVerifier.create(useCase.execute(self))
            .expectErrorMatches(code(ErrorCatalog.ACCOUNT_INVALID))
            .verify();
    }

    @Test
    void differentAccountCurrenciesMismatch() {
        when(core.getAccount(TARGET)).thenReturn(Mono.just(account(TARGET, "CUST-000456", "800.00", "USD")));
        StepVerifier.create(useCase.execute(command("150.00")))
            .expectErrorMatches(code(ErrorCatalog.CURRENCY_MISMATCH))
            .verify();
    }

    @Test
    void amountCurrencyDifferentFromAccountsMismatch() {
        TransferCommand usd = new TransferCommand("k", CUSTOMER, "c", SOURCE, TARGET, Money.parse("1", "USD"), null);
        StepVerifier.create(useCase.execute(usd))
            .expectErrorMatches(code(ErrorCatalog.CURRENCY_MISMATCH))
            .verify();
    }

    @Test
    void highFraudScoreRejects() {
        when(fraud.score(any())).thenReturn(Mono.just(new FraudAssessment(91, List.of("NEW_BENEFICIARY"))));
        StepVerifier.create(useCase.execute(command("150.00")))
            .expectErrorMatches(code(ErrorCatalog.FRAUD_REJECTED))
            .verify();
        verify(core, never()).debit(anyString(), anyString(), any(), anyString());
    }

    @Test
    void mediumFraudScoreStaysInReviewWithoutDebitEvenWithoutBalance() {
        when(fraud.score(any())).thenReturn(Mono.just(new FraudAssessment(67, List.of())));
        StepVerifier.create(useCase.execute(command("9500.00")))
            .assertNext(result -> {
                assertThat(result.transfer().status()).isEqualTo(TransferStatus.PENDING_REVIEW);
                assertThat(result.transfer().fraudScore()).isEqualTo(67);
            })
            .verifyComplete();
        verify(core, never()).debit(anyString(), anyString(), any(), anyString());
        assertThat(lastEvent()).isEqualTo(TransferEventType.TransferPendingReview);
    }

    @Test
    void fraudTimeoutStaysInReview() {
        when(fraud.score(any())).thenReturn(Mono.error(new TimeoutException()));
        StepVerifier.create(useCase.execute(command("150.00")))
            .assertNext(result -> {
                assertThat(result.transfer().status()).isEqualTo(TransferStatus.PENDING_REVIEW);
                assertThat(result.transfer().fraudScore()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void fraudWithoutAnswerStaysInReview() {
        when(fraud.score(any())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(command("150.00")).map(r -> r.transfer().status()))
            .expectNext(TransferStatus.PENDING_REVIEW)
            .verifyComplete();
    }

    @Test
    void insufficientBalanceRejectsWithoutDebit() {
        StepVerifier.create(useCase.execute(command("3000.00")))
            .expectErrorMatches(code(ErrorCatalog.INSUFFICIENT_FUNDS))
            .verify();
        verify(core, never()).debit(anyString(), anyString(), any(), anyString());
    }

    // ---------------------------------------------------------------- SAGA

    @Test
    void coreRejectingTheDebitRejectsTheTransfer() {
        when(core.debit(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.error(new BusinessException(ErrorCatalog.INSUFFICIENT_FUNDS)));
        StepVerifier.create(useCase.execute(command("150.00")))
            .expectErrorMatches(code(ErrorCatalog.INSUFFICIENT_FUNDS))
            .verify();
        verify(core, never()).credit(anyString(), anyString(), any(), anyString());
        assertThat(lastEvent()).isEqualTo(TransferEventType.TransferRejected);
    }

    @Test
    void debitTechnicalFailurePropagatesAndLeavesTransferReceived() {
        when(core.debit(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.error(new TechnicalException(ErrorCatalog.DEPENDENCY_UNAVAILABLE, "down", null)));
        StepVerifier.create(useCase.execute(command("150.00")))
            .expectError(TechnicalException.class)
            .verify();
        ArgumentCaptor<Transfer> saved = ArgumentCaptor.forClass(Transfer.class);
        verify(transfers).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TransferStatus.RECEIVED);
        verify(events, never()).publish(any());
    }

    @Test
    void creditFailureReversesTheDebitAndCompensates() {
        when(core.credit(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.error(new TechnicalException(ErrorCatalog.DEPENDENCY_UNAVAILABLE, "503", null)));
        StepVerifier.create(useCase.execute(command("150.00")))
            .assertNext(result -> {
                assertThat(result.created()).isTrue();
                assertThat(result.transfer().status()).isEqualTo(TransferStatus.COMPENSATED);
                assertThat(result.transfer().failureCode()).isEqualTo("CREDIT_FAILED");
            })
            .verifyComplete();
        verify(core).reverseDebit(eq("DBT-1"), any(), eq("CREDIT_FAILED"));
        assertThat(lastEvent()).isEqualTo(TransferEventType.TransferCompensated);
    }

    @Test
    void creditWithoutAnswerAlsoCompensates() {
        when(core.credit(anyString(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(command("150.00")).map(r -> r.transfer().status()))
            .expectNext(TransferStatus.COMPENSATED)
            .verifyComplete();
    }

    @Test
    void reversalFailurePropagatesAndLeavesTransferDebited() {
        when(core.credit(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.error(new TechnicalException(ErrorCatalog.DEPENDENCY_UNAVAILABLE, "503", null)));
        when(core.reverseDebit(anyString(), anyString(), anyString()))
            .thenReturn(Mono.error(new TechnicalException(ErrorCatalog.DEPENDENCY_UNAVAILABLE, "503", null)));
        StepVerifier.create(useCase.execute(command("150.00")).map(TransferResult::transfer))
            .expectError(TechnicalException.class)
            .verify();
        ArgumentCaptor<Transfer> saved = ArgumentCaptor.forClass(Transfer.class);
        verify(transfers, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TransferStatus.DEBITED);
    }
}
