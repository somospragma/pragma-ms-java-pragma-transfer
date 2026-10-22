package co.com.pragma.transfers.infrastructure.client.dto;

/** ScoreRequest de openapi/fraud.yaml. */
public record FraudScoreRequestDto(String operationId, String customerId, String channel, String sourceAccountId,
                                   String target, MoneyDto amount) {
}
