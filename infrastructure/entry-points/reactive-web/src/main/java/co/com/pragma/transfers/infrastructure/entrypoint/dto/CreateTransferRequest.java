package co.com.pragma.transfers.infrastructure.entrypoint.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** CreateTransferRequest del contrato v1 (additionalProperties: false). */
public record CreateTransferRequest(
    @NotNull @Pattern(regexp = ACCOUNT_PATTERN, message = "debe cumplir " + ACCOUNT_PATTERN) String sourceAccountId,
    @NotNull @Pattern(regexp = ACCOUNT_PATTERN, message = "debe cumplir " + ACCOUNT_PATTERN) String targetAccountId,
    @NotNull @Valid MoneyDto amount,
    @Size(max = 140) String description) {

    public static final String ACCOUNT_PATTERN = "^\\d{3}-\\d{10}$";
}
