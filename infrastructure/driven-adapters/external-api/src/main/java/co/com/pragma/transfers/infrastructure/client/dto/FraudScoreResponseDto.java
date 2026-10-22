package co.com.pragma.transfers.infrastructure.client.dto;

import java.util.List;

/** ScoreResponse de openapi/fraud.yaml. */
public record FraudScoreResponseDto(String operationId, Integer score, List<String> reasons) {
}
