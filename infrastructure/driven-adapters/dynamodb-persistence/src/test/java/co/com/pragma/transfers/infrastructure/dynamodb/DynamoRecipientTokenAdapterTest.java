package co.com.pragma.transfers.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.com.pragma.transfers.infrastructure.dynamodb.item.RecipientTokenItem;
import co.com.pragma.transfers.model.phone.RecipientToken;
import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferChannel;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.IgnoreNullsMode;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** Tabla recipient-tokens con el enhanced client simulado (sin Docker ni LocalStack). */
@SuppressWarnings("unchecked")
class DynamoRecipientTokenAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-15T15:04:05Z");

    private DynamoDbAsyncTable<RecipientTokenItem> table;
    private DynamoRecipientTokenAdapter adapter;

    @BeforeEach
    void setUp() {
        table = mock(DynamoDbAsyncTable.class);
        adapter = new DynamoRecipientTokenAdapter(table);
    }

    private static RecipientToken token(Instant usedAt, String usedBy) {
        return new RecipientToken("rt_1", "CUST-000123", "191-0007654321", "+51 9** *** 321", "Mar*** Qui***",
            NOW, NOW.plusSeconds(300), usedAt, usedBy);
    }

    @Test
    void saveWritesTheTtlInEpochSecondsWithoutTheFullPhone() {
        when(table.putItem(any(RecipientTokenItem.class))).thenReturn(CompletableFuture.completedFuture(null));

        StepVerifier.create(adapter.save(token(null, null))).expectNext(token(null, null)).verifyComplete();

        ArgumentCaptor<RecipientTokenItem> item = ArgumentCaptor.forClass(RecipientTokenItem.class);
        verify(table).putItem(item.capture());
        assertThat(item.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(300).getEpochSecond());
        assertThat(item.getValue().getTargetPhoneMasked()).isEqualTo("+51 9** *** 321");
        assertThat(item.getValue().getUsedAt()).isNull();
        assertThat(item.getValue().getCreatedAt()).isEqualTo("2026-10-15T15:04:05Z");
    }

    @Test
    void findByIdIsConsistentAndRoundTrips() {
        RecipientToken used = token(NOW.plusSeconds(10), "t1");
        when(table.getItem(any(GetItemEnhancedRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(RecipientTokenConverter.toItem(used)));
        StepVerifier.create(adapter.findById("rt_1")).expectNext(used).verifyComplete();

        ArgumentCaptor<GetItemEnhancedRequest> get = ArgumentCaptor.forClass(GetItemEnhancedRequest.class);
        verify(table).getItem(get.capture());
        assertThat(get.getValue().consistentRead()).isTrue();

        when(table.getItem(any(GetItemEnhancedRequest.class))).thenReturn(CompletableFuture.completedFuture(null));
        StepVerifier.create(adapter.findById("rt_2")).verifyComplete();
    }

    @Test
    void redeemIsAConditionalUpdateOnUsedAt() {
        when(table.updateItem(any(UpdateItemEnhancedRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(new RecipientTokenItem()));

        StepVerifier.create(adapter.redeem("rt_1", "CUST-000123", "t1", NOW)).expectNext(true).verifyComplete();

        ArgumentCaptor<UpdateItemEnhancedRequest<RecipientTokenItem>> request =
            ArgumentCaptor.forClass(UpdateItemEnhancedRequest.class);
        verify(table).updateItem(request.capture());
        UpdateItemEnhancedRequest<RecipientTokenItem> update = request.getValue();
        assertThat(update.conditionExpression().expression()).isEqualTo(DynamoRecipientTokenAdapter.REDEEM_CONDITION)
            .contains("attribute_not_exists(usedAt)").contains("expiresAt > :now")
            .contains("usedByTransferId = :transfer").contains("customerId = :customer");
        assertThat(update.conditionExpression().expressionValues().get(":now").n())
            .isEqualTo(Long.toString(NOW.getEpochSecond()));
        assertThat(update.conditionExpression().expressionValues().get(":customer").s()).isEqualTo("CUST-000123");
        assertThat(update.conditionExpression().expressionValues().get(":transfer").s()).isEqualTo("t1");
        assertThat(update.ignoreNullsMode()).isEqualTo(IgnoreNullsMode.SCALAR_ONLY);
        assertThat(update.item().getUsedAt()).isEqualTo(NOW.toString());
        assertThat(update.item().getUsedByTransferId()).isEqualTo("t1");
        assertThat(update.item().getCustomerId()).isNull();
        assertThat(update.item().getExpiresAt()).isNull();
    }

    @Test
    void failedConditionMeansTheTokenCannotBeUsed() {
        when(table.updateItem(any(UpdateItemEnhancedRequest.class))).thenReturn(CompletableFuture.failedFuture(
            new CompletionException(ConditionalCheckFailedException.builder().message("used").build())));
        StepVerifier.create(adapter.redeem("rt_1", "CUST-000123", "t2", NOW)).expectNext(false).verifyComplete();

        when(table.updateItem(any(UpdateItemEnhancedRequest.class))).thenReturn(CompletableFuture.failedFuture(
            ConditionalCheckFailedException.builder().message("used").build()));
        StepVerifier.create(adapter.redeem("rt_1", "CUST-000123", "t2", NOW)).expectNext(false).verifyComplete();
    }

    @Test
    void otherErrorsPropagate() {
        when(table.updateItem(any(UpdateItemEnhancedRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("throttled")));
        StepVerifier.create(adapter.redeem("rt_1", "CUST-000123", "t2", NOW))
            .expectError(IllegalStateException.class).verify();
    }

    @Test
    void phoneTransferKeepsTheMaskedPhoneInTheTransfersTable() {
        Transfer phone = Transfer.builder()
            .transferId("t1").idempotencyKey("k").customerId("CUST-000123")
            .sourceAccountId("191-0001234567").targetAccountId("191-0007654321")
            .targetPhoneMasked("+51 9** *** 321")
            .amount(new Money(new BigDecimal("45.50"), "PEN"))
            .status(TransferStatus.COMPLETED).channel(TransferChannel.PHONE)
            .businessDate(LocalDate.of(2026, 10, 15)).createdAt(NOW).updatedAt(NOW)
            .build();
        assertThat(TransferItemConverter.toItem(phone).getTargetPhoneMasked()).isEqualTo("+51 9** *** 321");
        assertThat(TransferItemConverter.toDomain(TransferItemConverter.toItem(phone))).isEqualTo(phone);
    }
}
