package co.com.pragma.transfers.model.phone;

import co.com.pragma.transfers.model.transfer.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * Parámetros del pago a celular (por configuración): RN-11 máximo por operación
 * y moneda admitida, y RN-12 vigencia del token de destinatario.
 */
public record PhoneTransferPolicy(BigDecimal perTransactionLimit, String currency, Duration tokenTtl) {

    public PhoneTransferPolicy {
        Objects.requireNonNull(perTransactionLimit, "perTransactionLimit");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(tokenTtl, "tokenTtl");
    }

    /** RN-11: el pago a celular solo admite la moneda configurada (PEN). */
    public boolean allowsCurrency(Money amount) {
        return amount.sameCurrency(currency);
    }

    /** RN-11: máximo por pago a celular (S/ 500.00). */
    public boolean exceedsLimit(Money amount) {
        return amount.isGreaterThan(perTransactionLimit);
    }
}
