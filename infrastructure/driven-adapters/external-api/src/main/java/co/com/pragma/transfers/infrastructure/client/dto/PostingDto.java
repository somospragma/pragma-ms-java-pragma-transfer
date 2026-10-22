package co.com.pragma.transfers.infrastructure.client.dto;

/** Posting de openapi/core-banking.yaml. */
public record PostingDto(String postingId, String operationId, String type, String status) {
}
