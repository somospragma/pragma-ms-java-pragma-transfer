package co.com.pragma.transfers.model.transfer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Parámetros de negocio de las reglas (se cargan por configuración):
 * RN-04 máximo por operación, RN-05 máximo diario por cliente, zona horaria
 * del día de negocio y vigencia de la llave de idempotencia (RN-08).
 * Los límites se expresan en la moneda de la operación (el taller no maneja
 * tipo de cambio).
 */
public record TransferPolicy(BigDecimal perTransactionLimit, BigDecimal dailyLimit, ZoneId zone,
                             Duration idempotencyTtl) {

    public TransferPolicy {
        Objects.requireNonNull(perTransactionLimit, "perTransactionLimit");
        Objects.requireNonNull(dailyLimit, "dailyLimit");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(idempotencyTtl, "idempotencyTtl");
    }

    /** RN-04. */
    public boolean exceedsPerTransaction(Money amount) {
        return amount.isGreaterThan(perTransactionLimit);
    }

    /** RN-05: acumulado del día + monto actual. */
    public boolean exceedsDaily(BigDecimal accumulatedToday, Money amount) {
        return amount.value().add(accumulatedToday).compareTo(dailyLimit) > 0;
    }

    public LocalDate businessDate(Instant instant) {
        return LocalDate.ofInstant(instant, zone);
    }
}
