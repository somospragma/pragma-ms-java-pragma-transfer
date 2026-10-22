package co.com.pragma.transfers.model.transfer;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/**
 * Agregado Transfer. Inmutable: cada cambio de estado produce una copia
 * ({@link #toBuilder()}). {@code businessDate} es el día (zona del negocio) que
 * se usa para el acumulado diario (RN-05).
 */
@Builder(toBuilder = true)
public record Transfer(
    String transferId,
    String idempotencyKey,
    String customerId,
    String sourceAccountId,
    String targetAccountId,
    Money amount,
    String description,
    TransferStatus status,
    TransferChannel channel,
    String failureCode,
    Integer fraudScore,
    String debitPostingId,
    String creditPostingId,
    String correlationId,
    LocalDate businessDate,
    Instant createdAt,
    Instant updatedAt) {

    /** operationId idempotente del débito en el core. */
    public String debitOperationId() {
        return "TRF-" + transferId + "-DEBIT";
    }

    public String creditOperationId() {
        return "TRF-" + transferId + "-CREDIT";
    }

    public String reversalOperationId() {
        return "TRF-" + transferId + "-REVERSAL";
    }

    /** operationId que se envía a antifraude. */
    public String fraudOperationId() {
        return "TRF-" + transferId;
    }

    public boolean belongsTo(String customer) {
        return customerId != null && customerId.equals(customer);
    }

    public Transfer withStatus(TransferStatus newStatus, Instant at) {
        return toBuilder().status(newStatus).updatedAt(at).build();
    }
}
