package co.com.pragma.transfers.infrastructure.client.dto;

/** Account de openapi/core-banking.yaml. */
public record CoreAccountDto(String accountId, String customerId, String holderName, String status,
                             String currency, MoneyDto availableBalance) {
}
