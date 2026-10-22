package co.com.pragma.transfers.model.idempotency;

import java.time.Instant;

/**
 * Reserva de un Idempotency-Key (RN-08): guarda la huella del cuerpo y el
 * transferId asignado. Vence a las 24 h (TTL).
 */
public record IdempotencyRecord(String idempotencyKey, String requestHash, String transferId,
                                Instant createdAt, Instant expiresAt) {

    public boolean matches(String fingerprint) {
        return requestHash != null && requestHash.equals(fingerprint);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
