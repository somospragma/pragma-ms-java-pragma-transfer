package co.com.pragma.transfers.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.com.pragma.transfers.model.account.Account;
import co.com.pragma.transfers.model.account.AccountStatus;
import co.com.pragma.transfers.model.account.Posting;
import co.com.pragma.transfers.model.event.TransferEvent;
import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.fraud.FraudAssessment;
import co.com.pragma.transfers.model.idempotency.IdempotencyRecord;
import co.com.pragma.transfers.model.phone.PhoneTransferCommand;
import co.com.pragma.transfers.model.phone.PhoneTransferPolicy;
import co.com.pragma.transfers.model.phone.RecipientToken;
import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferChannel;
import co.com.pragma.transfers.model.transfer.TransferPolicy;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import co.com.pragma.transfers.ports.ICoreBankingGateway;
import co.com.pragma.transfers.ports.IFraudGateway;
import co.com.pragma.transfers.ports.IIdempotencyGateway;
import co.com.pragma.transfers.ports.IRecipientTokenGateway;
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
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Pago a celular sobre el flujo real de la v1 (CreateTransferUseCase) con puertos simulados. */
@ExtendWith(MockitoExtension.class)
class CreatePhoneTransferUseCaseTest {

    private static final String CUSTOMER = "CUST-000123";
    private static final String SOURCE = "191-0001234567";
    private static final String TARGET = "191-0007654321";
    private static final String TOKEN = "rt_abc";
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
    @Mock
    private IRecipientTokenGateway tokens;

    private CreatePhoneTransferUseCase useCase;

    @BeforeEach
    void setUp() {
        TransferPolicy policy = new TransferPolicy(new BigDecimal("10000.00"), new BigDecimal("30000.00"),
            ZoneId.of("America/Lima"), Duration.ofHours(24));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CreateTransferUseCase flow = new CreateTransferUseCase(transfers, idempotency, core, fraud, events, policy,
            clock);
        useCase = new CreatePhoneTransferUseCase(flow, tokens, core,
            new PhoneTransferPolicy(new BigDecimal("500.00"), "PEN", Duration.ofMinutes(5)), policy, clock);
        lenient().when(idempotency.putIfAbsent(any())).thenReturn(Mono.empty());
        lenient().when(transfers.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(transfers.findById(anyString())).thenReturn(Mono.empty());
        lenient().when(transfers.sumDailyAmount(anyString(), any(), anyString())).thenReturn(Mono.just(BigDecimal.ZERO));
        lenient().when(events.publish(any())).thenReturn(Mono.empty());
        lenient().when(core.getAccount(SOURCE)).thenReturn(Mono.just(account(SOURCE, CUSTOMER)));
        lenient().when(core.getAccount(TARGET)).thenReturn(Mono.just(account(TARGET, "CUST-000456")));
        lenient().when(fraud.score(any())).thenReturn(Mono.just(new FraudAssessment(12, List.of())));
        lenient().when(core.debit(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just(new Posting("DBT-1", "op", "DEBIT", "POSTED")));
        lenient().when(core.credit(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just(new Posting("CRD-1", "op", "CREDIT", "POSTED")));
        lenient().when(tokens.findById(TOKEN)).thenReturn(Mono.just(token(TARGET, NOW.plusSeconds(120), null)));
        lenient().when(tokens.redeem(eq(TOKEN), eq(CUSTOMER), anyString(), eq(NOW))).thenReturn(Mono.just(true));
    }

    private static Account account(String id, String customer) {
        return new Account(id, customer, AccountStatus.ACTIVE, "PEN", new Money(new BigDecimal("2500.00"), "PEN"));
    }

    private static RecipientToken token(String target, Instant expiresAt, String usedBy) {
        return new RecipientToken(TOKEN, CUSTOMER, target, "+51 9** *** 321", "Mar*** Qui***", NOW.minusSeconds(60),
            expiresAt, usedBy == null ? null : NOW.minusSeconds(30), usedBy);
    }

    private static PhoneTransferCommand command(String amount, String currency) {
        return new PhoneTransferCommand("5d6e7f80-1a2b-4c3d-9e8f-7a6b5c4d3e2f", CUSTOMER, "corr-1", SOURCE, TOKEN,
            Money.parse(amount, currency), "Almuerzo");
    }

    private static Predicate<Throwable> code(ErrorCatalog error, String detail) {
        return e -> e instanceof BusinessException be && be.code().equals(error.code())
            && (detail == null || be.getMessage().equals(detail));
    }

    private void verifyNoMoneyMoved() {
        verify(core, never()).debit(anyString(), anyString(), any(), anyString());
        verify(transfers, never()).save(any());
    }

    @Test
    void completesPhoneTransferThroughTheV1Flow() {
        StepVerifier.create(useCase.execute(command("45.50", "PEN")))
            .assertNext(result -> {
                Transfer transfer = result.transfer();
                assertThat(result.created()).isTrue();
                assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
                assertThat(transfer.channel()).isEqualTo(TransferChannel.PHONE);
                assertThat(transfer.targetPhoneMasked()).isEqualTo("+51 9** *** 321");
                assertThat(transfer.targetAccountId()).isEqualTo(TARGET);
                assertThat(transfer.businessDate()).isEqualTo(LocalDate.of(2026, 10, 15));
            })
            .verifyComplete();
        verify(tokens).redeem(eq(TOKEN), eq(CUSTOMER), anyString(), eq(NOW));
        verify(core).debit(eq(SOURCE), anyString(), eq(Money.parse("45.50", "PEN")), anyString());
        verify(core).credit(eq(TARGET), anyString(), any(), anyString());
        ArgumentCaptor<TransferEvent> event = ArgumentCaptor.forClass(TransferEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().channel()).isEqualTo("PHONE");
    }

    @Test
    void unknownOrExpiredTokenIsRejectedWithoutDebit() {
        when(tokens.findById(TOKEN)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(command("45.50", "PEN")))
            .verifyErrorMatches(code(ErrorCatalog.RECIPIENT_TOKEN_EXPIRED, null));

        // Vencido pero todavía en la tabla (el TTL no lo borró): lo rechaza expiresAt.
        when(tokens.findById(TOKEN)).thenReturn(Mono.just(token(TARGET, NOW.minusSeconds(1), null)));
        StepVerifier.create(useCase.execute(command("45.50", "PEN")))
            .verifyErrorMatches(code(ErrorCatalog.RECIPIENT_TOKEN_EXPIRED, null));

        verify(tokens, never()).redeem(anyString(), anyString(), anyString(), any());
        verifyNoMoneyMoved();
    }

    @Test
    void tokenUsedByAnotherTransferIsRejected() {
        when(tokens.findById(TOKEN)).thenReturn(Mono.just(token(TARGET, NOW.plusSeconds(120), "other-transfer")));
        StepVerifier.create(useCase.execute(command("45.50", "PEN")))
            .verifyErrorMatches(code(ErrorCatalog.RECIPIENT_TOKEN_EXPIRED, null));
        verifyNoMoneyMoved();
    }

    @Test
    void lostRaceOnConditionalWriteIsRejected() {
        when(tokens.redeem(eq(TOKEN), eq(CUSTOMER), anyString(), eq(NOW))).thenReturn(Mono.just(false));
        StepVerifier.create(useCase.execute(command("45.50", "PEN")))
            .verifyErrorMatches(code(ErrorCatalog.RECIPIENT_TOKEN_EXPIRED, null));
        verifyNoMoneyMoved();
    }

    @Test
    void ownPhoneIsSelfTransfer() {
        when(tokens.findById(TOKEN)).thenReturn(Mono.just(token(SOURCE, NOW.plusSeconds(120), null)));
        StepVerifier.create(useCase.execute(command("45.50", "PEN")))
            .verifyErrorMatches(code(ErrorCatalog.SELF_TRANSFER, null));

        // Otra cuenta del mismo cliente también es pagarse a sí mismo.
        String otherOwnAccount = "191-0001111111";
        when(tokens.findById(TOKEN)).thenReturn(Mono.just(token(otherOwnAccount, NOW.plusSeconds(120), null)));
        when(core.getAccount(otherOwnAccount)).thenReturn(Mono.just(account(otherOwnAccount, CUSTOMER)));
        StepVerifier.create(useCase.execute(command("45.50", "PEN")))
            .verifyErrorMatches(code(ErrorCatalog.SELF_TRANSFER, null));

        verify(tokens, never()).redeem(anyString(), anyString(), anyString(), any());
        verifyNoMoneyMoved();
    }

    @Test
    void rn11LimitAndCurrencyAreCheckedBeforeUsingTheToken() {
        StepVerifier.create(useCase.execute(command("500.01", "PEN")))
            .verifyErrorMatches(code(ErrorCatalog.LIMIT_PER_TX_EXCEEDED, "El máximo por pago a celular es S/ 500.00"));
        StepVerifier.create(useCase.execute(command("10.00", "USD")))
            .verifyErrorMatches(code(ErrorCatalog.LIMIT_PER_TX_EXCEEDED, "El pago a celular solo admite PEN"));
        verify(tokens, never()).redeem(anyString(), anyString(), anyString(), any());
        verifyNoMoneyMoved();
    }

    @Test
    void exactlyFiveHundredIsAllowed() {
        StepVerifier.create(useCase.execute(command("500.00", "PEN")))
            .assertNext(result -> assertThat(result.transfer().status()).isEqualTo(TransferStatus.COMPLETED))
            .verifyComplete();
    }

    @Test
    void retryWithSameKeyAfterAFailureBeforeSavingRedeemsTheTokenAgain() {
        PhoneTransferCommand command = command("45.50", "PEN");
        when(idempotency.putIfAbsent(any())).thenReturn(Mono.just(new IdempotencyRecord(command.idempotencyKey(),
            command.fingerprint(), "t-previous", NOW, NOW.plus(Duration.ofHours(24)))));
        // El intento anterior ya dejó el token a nombre de t-previous.
        when(tokens.findById(TOKEN)).thenReturn(Mono.just(token(TARGET, NOW.minusSeconds(1), "t-previous")));
        when(tokens.redeem(TOKEN, CUSTOMER, "t-previous", NOW)).thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute(command))
            .assertNext(result -> {
                assertThat(result.transfer().transferId()).isEqualTo("t-previous");
                assertThat(result.transfer().status()).isEqualTo(TransferStatus.COMPLETED);
            })
            .verifyComplete();
    }

    @Test
    void replayOfACompletedPhoneTransferReturnsTheOriginalWithoutTouchingTheToken() {
        PhoneTransferCommand command = command("45.50", "PEN");
        when(idempotency.putIfAbsent(any())).thenReturn(Mono.just(new IdempotencyRecord(command.idempotencyKey(),
            command.fingerprint(), "t-done", NOW, NOW.plus(Duration.ofHours(24)))));
        Transfer done = Transfer.builder().transferId("t-done").customerId(CUSTOMER).sourceAccountId(SOURCE)
            .targetAccountId(TARGET).targetPhoneMasked("+51 9** *** 321").amount(command.amount())
            .status(TransferStatus.COMPLETED).channel(TransferChannel.PHONE).createdAt(NOW).build();
        when(transfers.findById("t-done")).thenReturn(Mono.just(done));

        StepVerifier.create(useCase.execute(command))
            .assertNext(result -> {
                assertThat(result.created()).isFalse();
                assertThat(result.transfer()).isEqualTo(done);
            })
            .verifyComplete();
        verify(tokens, never()).findById(anyString());
    }

    @Test
    void sameKeyWithAnotherBodyIsConflict() {
        PhoneTransferCommand command = command("45.50", "PEN");
        when(idempotency.putIfAbsent(any())).thenReturn(Mono.just(new IdempotencyRecord(command.idempotencyKey(),
            "otra-huella", "t-x", NOW, NOW.plus(Duration.ofHours(24)))));
        StepVerifier.create(useCase.execute(command))
            .verifyErrorMatches(code(ErrorCatalog.IDEMPOTENCY_KEY_REUSED, null));
    }

    @Test
    void rejectsSourceAccountOfAnotherCustomerWithoutRedeemingToken() {
        when(core.getAccount(SOURCE)).thenReturn(Mono.just(account(SOURCE, "CUST-000999")));
        StepVerifier.create(useCase.execute(command("45.50", "PEN")))
            .expectErrorMatches(code(ErrorCatalog.ACCOUNT_NOT_OWNED, null))
            .verify();
        verify(tokens, never()).redeem(anyString(), anyString(), anyString(), any());
        verifyNoMoneyMoved();
    }

    @Test
    void rejectsUnknownSourceAccountWithoutRedeemingToken() {
        when(core.getAccount(SOURCE)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(command("45.50", "PEN")))
            .expectErrorMatches(code(ErrorCatalog.ACCOUNT_NOT_OWNED, null))
            .verify();
        verify(tokens, never()).redeem(anyString(), anyString(), anyString(), any());
        verifyNoMoneyMoved();
    }
}
