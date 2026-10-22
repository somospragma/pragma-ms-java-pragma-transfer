package co.com.pragma.transfers.model.phone;

import java.time.Instant;

/**
 * Token de destinatario (RN-12): resultado de resolver un celular, de un solo uso
 * y con vigencia de 5 minutos. Guarda la cuenta destino del lado del servidor para
 * que el cliente nunca la vea. {@code usedByTransferId} permite que el reintento
 * de la misma transferencia (mismo Idempotency-Key) vuelva a pasar el token.
 */
public record RecipientToken(
    String token,
    String customerId,
    String targetAccountId,
    String targetPhoneMasked,
    String maskedName,
    Instant createdAt,
    Instant expiresAt,
    Instant usedAt,
    String usedByTransferId) {

    public static final String PREFIX = "rt_";

    /** El micro valida el vencimiento aunque el TTL de DynamoDB todavía no haya borrado el ítem. */
    public boolean isExpired(Instant now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    /** Vigente y sin usar, o ya usado por esta misma transferencia (reintento idempotente). */
    public boolean isRedeemableBy(String customer, String transferId, Instant now) {
        if (customerId == null || !customerId.equals(customer)) {
            return false;
        }
        if (isUsed()) {
            return transferId != null && transferId.equals(usedByTransferId);
        }
        return !isExpired(now);
    }

    @Override
    public String toString() {
        return "RecipientToken[customerId=" + customerId + ", phone=" + targetPhoneMasked + ", expiresAt="
            + expiresAt + ", used=" + isUsed() + "]";
    }
}
