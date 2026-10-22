package co.com.pragma.transfers.infrastructure.entrypoint.dto;

import co.com.pragma.transfers.model.transfer.Transfer;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Transfer del contrato v1. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferResponse(String transferId, String status, String channel, MoneyDto amount,
                               String sourceAccountId, String targetAccountId, String description,
                               String failureCode, String createdAt, String updatedAt) {

    public static TransferResponse from(Transfer t) {
        return new TransferResponse(t.transferId(), t.status().name(), t.channel().name(),
            new MoneyDto(t.amount().asString(), t.amount().currency()), t.sourceAccountId(), t.targetAccountId(),
            t.description(), t.failureCode(), t.createdAt().toString(),
            t.updatedAt() == null ? null : t.updatedAt().toString());
    }
}
