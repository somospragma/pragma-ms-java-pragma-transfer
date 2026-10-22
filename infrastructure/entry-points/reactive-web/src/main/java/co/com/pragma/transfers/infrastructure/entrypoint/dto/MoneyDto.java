package co.com.pragma.transfers.infrastructure.entrypoint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Money del contrato. El formato del valor (RN-03) lo valida el dominio para
 * responder AMOUNT_INVALID; aquí solo se exige presencia y moneda válida.
 */
public record MoneyDto(
    @NotBlank String value,
    @NotNull @Pattern(regexp = "PEN|USD", message = "debe ser PEN o USD") String currency) {
}
