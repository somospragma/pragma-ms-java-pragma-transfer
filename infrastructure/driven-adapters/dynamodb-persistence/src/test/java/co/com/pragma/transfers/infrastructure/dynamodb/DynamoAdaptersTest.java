package co.com.pragma.transfers.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.com.pragma.transfers.infrastructure.dynamodb.item.IdempotencyItem;
import co.com.pragma.transfers.infrastructure.dynamodb.item.TransferItem;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** Adaptadores DynamoDB con el enhanced client simulado (sin Docker ni LocalStack). */
@SuppressWarnings("unchecked")
class DynamoAdaptersTest {

    private static final Instant NOW = Instant.parse("2026-10-15T15:04:05Z");

    private DynamoDbAsyncTable<TransferItem> transferTable;
    private DynamoDbAsyncIndex<TransferItem> index;
    private DynamoDbAsyncTable<IdempotencyItem> idempotencyTable;
    private DynamoTransferAdapter transfers;
    private DynamoIdempotencyAdapter idempotency;

    @BeforeEach
    void setUp() {
        transferTable = mock(DynamoDbAsyncTable.class);
        index = mock(DynamoDbAsyncIndex.class);
        idempotencyTable = mock(DynamoDbAsyncTable.class);
        transfers = new DynamoTransferAdapter(transferTable);
        idempotency = new DynamoIdempotencyAdapter(idempotencyTable, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Transfer transfer(String id, TransferStatus status, String amount, String currency) {
        return Transfer.builder()
            .transferId(id)
            .idempotencyKey("k-" + id)
            .customerId("CUST-000123")
            .sourceAccountId("191-0001234567")
            .targetAccountId("191-0007654321")
            .amount(new Money(new BigDecimal(amount), currency))
            .description("Pago")
            .status(status)
            .channel(TransferChannel.ACCOUNT)
            .failureCode(status == TransferStatus.REJECTED ? "FRAUD_REJECTED" : null)
            .fraudScore(12)
            .debitPostingId("DBT-1")
            .creditPostingId("CRD-1")
            .correlationId("corr")
            .businessDate(LocalDate.of(2026, 10, 15))
            .createdAt(NOW)
            .updatedAt(status == TransferStatus.RECEIVED ? null : NOW)
            .build();
    }

    private void givenIndex(String name, List<TransferItem> items) {
        when(transferTable.index(name)).thenReturn(index);
        when(index.query(any(QueryEnhancedRequest.class)))
            .thenReturn(SdkPublisher.adapt(Flux.just(Page.create(items))));
    }

    @Test
    void saveWritesTheItemWithIndexKeys() {
        when(transferTable.putItem(any(TransferItem.class))).thenReturn(CompletableFuture.completedFuture(null));
        Transfer transfer = transfer("t1", TransferStatus.COMPLETED, "150.00", "PEN");

        StepVerifier.create(transfers.save(transfer)).expectNext(transfer).verifyComplete();

        ArgumentCaptor<TransferItem> item = ArgumentCaptor.forClass(TransferItem.class);
        verify(transferTable).putItem(item.capture());
        assertThat(item.getValue().getCustomerDay()).isEqualTo("CUST-000123#2026-10-15");
        assertThat(item.getValue().getAmount()).isEqualTo("150.00");
        assertThat(item.getValue().getCreatedAt()).isEqualTo("2026-10-15T15:04:05Z");
    }

    @Test
    void findByIdRoundTripsTheItem() {
        Transfer transfer = transfer("t1", TransferStatus.RECEIVED, "150.00", "PEN");
        when(transferTable.getItem(any(Key.class)))
            .thenReturn(CompletableFuture.completedFuture(TransferItemConverter.toItem(transfer)));
        StepVerifier.create(transfers.findById("t1")).expectNext(transfer).verifyComplete();

        when(transferTable.getItem(any(Key.class))).thenReturn(CompletableFuture.completedFuture(null));
        StepVerifier.create(transfers.findById("t2")).verifyComplete();
    }

    @Test
    void findBySourceAccountQueriesTheAccountIndex() {
        givenIndex(TransferItem.GSI_SOURCE_ACCOUNT, List.of(
            TransferItemConverter.toItem(transfer("t2", TransferStatus.COMPLETED, "20.00", "PEN")),
            TransferItemConverter.toItem(transfer("t1", TransferStatus.REJECTED, "10.00", "PEN"))));

        StepVerifier.create(transfers.findBySourceAccount("191-0001234567").map(Transfer::transferId))
            .expectNext("t2", "t1")
            .verifyComplete();

        ArgumentCaptor<QueryEnhancedRequest> request = ArgumentCaptor.forClass(QueryEnhancedRequest.class);
        verify(index).query(request.capture());
        assertThat(request.getValue().scanIndexForward()).isFalse();
    }

    @Test
    void sumDailyAmountCountsOnlyEligibleStatusesInTheSameCurrency() {
        givenIndex(TransferItem.GSI_CUSTOMER_DAY, List.of(
            TransferItemConverter.toItem(transfer("a", TransferStatus.COMPLETED, "100.00", "PEN")),
            TransferItemConverter.toItem(transfer("b", TransferStatus.PENDING_REVIEW, "50.50", "PEN")),
            TransferItemConverter.toItem(transfer("c", TransferStatus.REJECTED, "999.00", "PEN")),
            TransferItemConverter.toItem(transfer("d", TransferStatus.COMPENSATED, "999.00", "PEN")),
            TransferItemConverter.toItem(transfer("e", TransferStatus.COMPLETED, "999.00", "USD"))));

        StepVerifier.create(transfers.sumDailyAmount("CUST-000123", LocalDate.of(2026, 10, 15), "PEN"))
            .expectNext(new BigDecimal("150.50"))
            .verifyComplete();
    }

    private IdempotencyRecord reservation() {
        return new IdempotencyRecord("key-1", "hash", "t1", NOW, NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void reservationIsAConditionalPutWithTtl() {
        when(idempotencyTable.putItem(any(PutItemEnhancedRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        StepVerifier.create(idempotency.putIfAbsent(reservation())).verifyComplete();

        ArgumentCaptor<PutItemEnhancedRequest<IdempotencyItem>> request =
            ArgumentCaptor.forClass(PutItemEnhancedRequest.class);
        verify(idempotencyTable).putItem(request.capture());
        assertThat(request.getValue().conditionExpression().expression())
            .contains("attribute_not_exists(idempotencyKey)");
        assertThat(request.getValue().conditionExpression().expressionValues().get(":now").n())
            .isEqualTo(Long.toString(NOW.getEpochSecond()));
        assertThat(request.getValue().item().getExpiresAt())
            .isEqualTo(NOW.plus(Duration.ofHours(24)).getEpochSecond());
    }

    @Test
    void existingKeyReturnsTheStoredRecord() {
        when(idempotencyTable.putItem(any(PutItemEnhancedRequest.class))).thenReturn(CompletableFuture.failedFuture(
            new CompletionException(ConditionalCheckFailedException.builder().message("exists").build())));
        IdempotencyRecord stored = new IdempotencyRecord("key-1", "other", "t0", NOW,
            NOW.plus(Duration.ofHours(1)));
        when(idempotencyTable.getItem(any(GetItemEnhancedRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(TransferItemConverter.toItem(stored)));

        StepVerifier.create(idempotency.putIfAbsent(reservation())).expectNext(stored).verifyComplete();

        ArgumentCaptor<GetItemEnhancedRequest> get = ArgumentCaptor.forClass(GetItemEnhancedRequest.class);
        verify(idempotencyTable).getItem(get.capture());
        assertThat(get.getValue().consistentRead()).isTrue();
    }

    @Test
    void conditionFailureWithoutWrapperIsAlsoHandled() {
        when(idempotencyTable.putItem(any(PutItemEnhancedRequest.class))).thenReturn(CompletableFuture.failedFuture(
            ConditionalCheckFailedException.builder().message("exists").build()));
        when(idempotencyTable.getItem(any(GetItemEnhancedRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(TransferItemConverter.toItem(reservation())));
        StepVerifier.create(idempotency.putIfAbsent(reservation())).expectNext(reservation()).verifyComplete();
    }

    @Test
    void otherDynamoErrorsPropagate() {
        when(idempotencyTable.putItem(any(PutItemEnhancedRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("throttled")));
        StepVerifier.create(idempotency.putIfAbsent(reservation())).expectError(IllegalStateException.class).verify();
        verify(idempotencyTable, org.mockito.Mockito.never()).getItem(any(GetItemEnhancedRequest.class));
    }
}
