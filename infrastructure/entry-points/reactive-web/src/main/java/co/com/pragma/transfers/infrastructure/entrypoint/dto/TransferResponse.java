package co.com.pragma.transfers.infrastructure.entrypoint.dto;

import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferChannel;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Transfer del contrato v3. {@code targetAccountId} solo se devuelve en el canal
 * ACCOUNT; en PHONE se devuelve {@code targetPhoneMasked} y la cuenta resuelta por
 * el directorio nunca sale del servicio.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferResponse(String transferId, String status, String channel, MoneyDto amount,
                               String sourceAccountId, String targetAccountId, String targetPhoneMasked,
                               String description, String failureCode, String createdAt, String updatedAt) {

    public static TransferResponse from(Transfer t) {
        boolean phone = t.channel() == TransferChannel.PHONE;
        return new TransferResponse(t.transferId(), t.status().name(), t.channel().name(),
            new MoneyDto(t.amount().asString(), t.amount().currency()), t.sourceAccountId(),
            phone ? null : t.targetAccountId(), phone ? t.targetPhoneMasked() : null,
            t.description(), t.failureCode(), t.createdAt().toString(),
            t.updatedAt() == null ? null : t.updatedAt().toString());
    }
}
