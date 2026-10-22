package co.com.pragma.transfers.model.transfer;

import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Monto con moneda. Se maneja como decimal exacto (nunca double) con escala 2.
 * RN-03: mayor a 0 y con máximo 2 decimales.
 */
public record Money(BigDecimal value, String currency) {

    private static final Pattern FORMAT = Pattern.compile("^\\d{1,12}(\\.\\d{1,2})?$");
    private static final String FIELD = "amount.value";

    public Money {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(currency, "currency");
        value = value.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Construye el monto desde el string del contrato aplicando RN-03. */
    public static Money parse(String value, String currency) {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw invalid("debe cumplir ^\\d{1,12}(\\.\\d{1,2})?$");
        }
        BigDecimal amount = new BigDecimal(value);
        if (amount.signum() <= 0) {
            throw invalid("debe ser mayor a 0");
        }
        return new Money(amount, currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        return new Money(value.add(other.value), currency);
    }

    public boolean isGreaterThan(BigDecimal other) {
        return value.compareTo(other) > 0;
    }

    public boolean sameCurrency(String other) {
        return currency.equals(other);
    }

    /** Representación del contrato: "150.00". */
    public String asString() {
        return value.toPlainString();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCatalog.AMOUNT_INVALID,
            ErrorCatalog.AMOUNT_INVALID.defaultMessage(), Map.of(FIELD, message));
    }
}
