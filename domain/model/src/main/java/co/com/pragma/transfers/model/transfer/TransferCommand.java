package co.com.pragma.transfers.model.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Orden de crear una transferencia (POST /api/v1/transfers) ya validada en
 * forma. La huella ({@link #fingerprint()}) identifica el cuerpo para RN-08:
 * misma llave + misma huella = repetición; huella distinta = 409.
 */
public record TransferCommand(
    String idempotencyKey,
    String customerId,
    String correlationId,
    String sourceAccountId,
    String targetAccountId,
    Money amount,
    String description) {

    public String fingerprint() {
        String canonical = String.join("|",
            customerId,
            sourceAccountId,
            targetAccountId,
            amount.asString(),
            amount.currency(),
            description == null ? "" : description);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
