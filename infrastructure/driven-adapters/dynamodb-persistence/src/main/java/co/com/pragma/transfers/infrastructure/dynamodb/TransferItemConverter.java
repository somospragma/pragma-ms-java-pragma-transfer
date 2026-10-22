package co.com.pragma.transfers.infrastructure.dynamodb;

import co.com.pragma.transfers.infrastructure.dynamodb.item.IdempotencyItem;
import co.com.pragma.transfers.infrastructure.dynamodb.item.TransferItem;
import co.com.pragma.transfers.model.idempotency.IdempotencyRecord;
import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferChannel;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Conversión entre el modelo de dominio y los ítems de DynamoDB. */
final class TransferItemConverter {

    private TransferItemConverter() {
    }

    static String customerDay(String customerId, LocalDate businessDate) {
        return customerId + "#" + businessDate;
    }

    static TransferItem toItem(Transfer transfer) {
        TransferItem item = new TransferItem();
        item.setTransferId(transfer.transferId());
        item.setIdempotencyKey(transfer.idempotencyKey());
        item.setCustomerId(transfer.customerId());
        item.setSourceAccountId(transfer.sourceAccountId());
        item.setTargetAccountId(transfer.targetAccountId());
        item.setAmount(transfer.amount().asString());
        item.setCurrency(transfer.amount().currency());
        item.setDescription(transfer.description());
        item.setStatus(transfer.status().name());
        item.setChannel(transfer.channel().name());
        item.setFailureCode(transfer.failureCode());
        item.setFraudScore(transfer.fraudScore());
        item.setDebitPostingId(transfer.debitPostingId());
        item.setCreditPostingId(transfer.creditPostingId());
        item.setCorrelationId(transfer.correlationId());
        item.setBusinessDate(transfer.businessDate().toString());
        item.setCustomerDay(customerDay(transfer.customerId(), transfer.businessDate()));
        item.setCreatedAt(transfer.createdAt().toString());
        item.setUpdatedAt(transfer.updatedAt() == null ? null : transfer.updatedAt().toString());
        return item;
    }

    static Transfer toDomain(TransferItem item) {
        return Transfer.builder()
            .transferId(item.getTransferId())
            .idempotencyKey(item.getIdempotencyKey())
            .customerId(item.getCustomerId())
            .sourceAccountId(item.getSourceAccountId())
            .targetAccountId(item.getTargetAccountId())
            .amount(new Money(new BigDecimal(item.getAmount()), item.getCurrency()))
            .description(item.getDescription())
            .status(TransferStatus.valueOf(item.getStatus()))
            .channel(TransferChannel.valueOf(item.getChannel()))
            .failureCode(item.getFailureCode())
            .fraudScore(item.getFraudScore())
            .debitPostingId(item.getDebitPostingId())
            .creditPostingId(item.getCreditPostingId())
            .correlationId(item.getCorrelationId())
            .businessDate(LocalDate.parse(item.getBusinessDate()))
            .createdAt(Instant.parse(item.getCreatedAt()))
            .updatedAt(item.getUpdatedAt() == null ? null : Instant.parse(item.getUpdatedAt()))
            .build();
    }

    static IdempotencyItem toItem(IdempotencyRecord idempotencyRecord) {
        IdempotencyItem item = new IdempotencyItem();
        item.setIdempotencyKey(idempotencyRecord.idempotencyKey());
        item.setRequestHash(idempotencyRecord.requestHash());
        item.setTransferId(idempotencyRecord.transferId());
        item.setCreatedAt(idempotencyRecord.createdAt().toString());
        item.setExpiresAt(idempotencyRecord.expiresAt().getEpochSecond());
        return item;
    }

    static IdempotencyRecord toDomain(IdempotencyItem item) {
        return new IdempotencyRecord(item.getIdempotencyKey(), item.getRequestHash(), item.getTransferId(),
            Instant.parse(item.getCreatedAt()), Instant.ofEpochSecond(item.getExpiresAt()));
    }
}
