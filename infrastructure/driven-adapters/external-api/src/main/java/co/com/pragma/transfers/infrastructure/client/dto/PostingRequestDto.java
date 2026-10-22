package co.com.pragma.transfers.infrastructure.client.dto;

/** PostingRequest de openapi/core-banking.yaml (débito o crédito). */
public record PostingRequestDto(String operationId, MoneyDto amount, String reference) {
}
