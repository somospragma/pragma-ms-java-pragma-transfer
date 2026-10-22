package co.com.pragma.transfers.infrastructure.entrypoint.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** CreatePhoneTransferRequest del contrato v3 (additionalProperties: false). */
public record CreatePhoneTransferRequest(
    @NotNull @Pattern(regexp = CreateTransferRequest.ACCOUNT_PATTERN,
        message = "debe cumplir " + CreateTransferRequest.ACCOUNT_PATTERN) String sourceAccountId,
    @NotBlank @Size(max = 64) String recipientToken,
    @NotNull @Valid MoneyDto amount,
    @Size(max = 140) String description) {
}
