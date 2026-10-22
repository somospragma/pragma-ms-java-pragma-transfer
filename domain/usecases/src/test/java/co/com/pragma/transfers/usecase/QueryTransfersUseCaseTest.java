package co.com.pragma.transfers.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.com.pragma.transfers.model.account.Account;
import co.com.pragma.transfers.model.account.AccountStatus;
import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferChannel;
import co.com.pragma.transfers.model.transfer.TransferPolicy;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import co.com.pragma.transfers.ports.ICoreBankingGateway;
import co.com.pragma.transfers.ports.ITransferGateway;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class QueryTransfersUseCaseTest {

    private static final String CUSTOMER = "CUST-000123";
    private static final String ACCOUNT = "191-0001234567";

    @Mock
    private ITransferGateway transfers;
    @Mock
    private ICoreBankingGateway core;

    private GetTransferUseCase get;
    private ListAccountTransfersUseCase list;

    @BeforeEach
    void setUp() {
        TransferPolicy policy = new TransferPolicy(new BigDecimal("10000"), new BigDecimal("30000"),
            ZoneId.of("America/Lima"), Duration.ofHours(24));
        get = new GetTransferUseCase(transfers);
        list = new ListAccountTransfersUseCase(core, transfers, policy);
    }

    private static Transfer transfer(String id, String customer, TransferStatus status, String createdAt) {
        return Transfer.builder()
            .transferId(id)
            .customerId(customer)
            .sourceAccountId(ACCOUNT)
            .targetAccountId("191-0007654321")
            .amount(Money.parse("10", "PEN"))
            .status(status)
            .channel(TransferChannel.ACCOUNT)
            .businessDate(LocalDate.of(2026, 10, 1))
            .createdAt(Instant.parse(createdAt))
            .build();
    }

    private static Predicate<Throwable> code(ErrorCatalog error) {
        return e -> e instanceof BusinessException be && be.code().equals(error.code());
    }

    @Test
    void getReturnsOwnTransfer() {
        when(transfers.findById("t1")).thenReturn(Mono.just(transfer("t1", CUSTOMER, TransferStatus.COMPLETED,
            "2026-10-01T10:00:00Z")));
        StepVerifier.create(get.execute("t1", CUSTOMER).map(Transfer::transferId)).expectNext("t1").verifyComplete();
    }

    @Test
    void getOfAnotherCustomerIsForbiddenAndMissingIsNotFound() {
        when(transfers.findById("t1")).thenReturn(Mono.just(transfer("t1", "CUST-2", TransferStatus.COMPLETED,
            "2026-10-01T10:00:00Z")));
        when(transfers.findById("t2")).thenReturn(Mono.empty());
        StepVerifier.create(get.execute("t1", CUSTOMER)).expectErrorMatches(code(ErrorCatalog.ACCOUNT_NOT_OWNED))
            .verify();
        StepVerifier.create(get.execute("t2", CUSTOMER)).expectErrorMatches(code(ErrorCatalog.TRANSFER_NOT_FOUND))
            .verify();
    }

    @Test
    void listSortsFiltersAndPaginates() {
        when(core.getAccount(ACCOUNT)).thenReturn(Mono.just(new Account(ACCOUNT, CUSTOMER, AccountStatus.ACTIVE,
            "PEN", Money.parse("1", "PEN"))));
        when(transfers.findBySourceAccount(ACCOUNT)).thenReturn(Flux.just(
            transfer("old", CUSTOMER, TransferStatus.COMPLETED, "2026-09-30T10:00:00Z"),
            transfer("new", CUSTOMER, TransferStatus.COMPLETED, "2026-10-02T10:00:00Z"),
            transfer("rej", CUSTOMER, TransferStatus.REJECTED, "2026-10-01T10:00:00Z")));

        StepVerifier.create(list.execute(ACCOUNT, CUSTOMER, null, null, null, 0, 2))
            .assertNext(page -> {
                assertThat(page.content()).extracting(Transfer::transferId).containsExactly("new", "rej");
                assertThat(page.totalElements()).isEqualTo(3);
                assertThat(page.totalPages()).isEqualTo(2);
            })
            .verifyComplete();

        StepVerifier.create(list.execute(ACCOUNT, CUSTOMER, TransferStatus.COMPLETED,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), 0, 20))
            .assertNext(page -> assertThat(page.content()).extracting(Transfer::transferId).containsExactly("new"))
            .verifyComplete();
    }

    @Test
    void listOfAccountNotOwnedOrMissingIsForbidden() {
        when(core.getAccount(ACCOUNT)).thenReturn(Mono.just(new Account(ACCOUNT, "CUST-2", AccountStatus.ACTIVE,
            "PEN", Money.parse("1", "PEN"))));
        StepVerifier.create(list.execute(ACCOUNT, CUSTOMER, null, null, null, 0, 20))
            .expectErrorMatches(code(ErrorCatalog.ACCOUNT_NOT_OWNED))
            .verify();
        when(core.getAccount(ACCOUNT)).thenReturn(Mono.empty());
        StepVerifier.create(list.execute(ACCOUNT, CUSTOMER, null, null, null, 0, 20))
            .expectErrorMatches(code(ErrorCatalog.ACCOUNT_NOT_OWNED))
            .verify();
    }

    @Test
    void listValidatesPagingAndDates() {
        StepVerifier.create(list.execute(ACCOUNT, CUSTOMER, null, null, null, -1, 20))
            .expectErrorMatches(code(ErrorCatalog.VALIDATION_ERROR)).verify();
        StepVerifier.create(list.execute(ACCOUNT, CUSTOMER, null, null, null, 0, 0))
            .expectErrorMatches(code(ErrorCatalog.VALIDATION_ERROR)).verify();
        StepVerifier.create(list.execute(ACCOUNT, CUSTOMER, null, null, null, 0, 101))
            .expectErrorMatches(code(ErrorCatalog.VALIDATION_ERROR)).verify();
        StepVerifier.create(list.execute(ACCOUNT, CUSTOMER, null, LocalDate.of(2026, 10, 2),
                LocalDate.of(2026, 10, 1), 0, 20))
            .expectErrorMatches(code(ErrorCatalog.VALIDATION_ERROR)).verify();
        verify(core, never()).getAccount(ACCOUNT);
    }
}
