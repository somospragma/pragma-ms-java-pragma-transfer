package co.com.pragma.transfers.model.phone;

import co.com.pragma.transfers.model.transfer.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Orden de pago a celular (POST /api/v1/phone-transfers) ya validada en forma.
 * La huella identifica el cuerpo para RN-08; incluye el canal para que la misma
 * llave usada en /transfers y en /phone-transfers nunca se confunda.
 */
public record PhoneTransferCommand(
    String idempotencyKey,
    String customerId,
    String correlationId,
    String sourceAccountId,
    String recipientToken,
    Money amount,
    String description) {

    public String fingerprint() {
        String canonical = String.join("|",
            "PHONE",
            customerId,
            sourceAccountId,
            recipientToken,
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
