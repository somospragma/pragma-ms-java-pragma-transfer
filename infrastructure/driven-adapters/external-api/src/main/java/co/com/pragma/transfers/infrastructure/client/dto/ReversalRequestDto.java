package co.com.pragma.transfers.infrastructure.client.dto;

/** Cuerpo de POST /core/v1/debits/{debitId}/reversal. */
public record ReversalRequestDto(String operationId, String reason) {
}
