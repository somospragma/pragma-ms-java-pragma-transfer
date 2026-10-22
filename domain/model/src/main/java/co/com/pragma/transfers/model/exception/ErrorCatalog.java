package co.com.pragma.transfers.model.exception;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Catalogo versionado de codigos de negocio (ERR-BP-008), alineado con el enum
 * {@code ErrorCode} de openapi/ms-transfers-v3.yaml. Los consumidores reaccionan
 * por {@code code}, nunca por el texto. El status HTTP vive aqui (dato del
 * catalogo, no dependencia de framework) y lo traduce el entry-point.
 */
public enum ErrorCatalog {

    VALIDATION_ERROR(400, "Petición inválida", "La petición no cumple el contrato"),
    AMOUNT_INVALID(400, "Monto inválido", "El monto debe ser mayor a 0 y tener máximo 2 decimales"),
    PHONE_INVALID(400, "Celular inválido", "El celular debe tener el formato +51 seguido de 9 dígitos que empiezan en 9"),
    UNAUTHORIZED(401, "No autenticado", "Falta el header X-Customer-Id"),
    ACCOUNT_NOT_OWNED(403, "Cuenta no autorizada", "La cuenta no pertenece al cliente"),
    TRANSFER_NOT_FOUND(404, "Transferencia no encontrada", "La transferencia no existe"),
    RECIPIENT_NOT_FOUND(404, "Celular no afiliado", "El celular no está afiliado al directorio"),
    /** Fuera del contrato: rutas inexistentes del servicio. */
    ROUTE_NOT_FOUND(404, "Recurso no encontrado", "La ruta solicitada no existe"),
    IDEMPOTENCY_KEY_REUSED(409, "Idempotency-Key reutilizado",
        "El Idempotency-Key ya se usó con un cuerpo distinto"),
    ACCOUNT_INVALID(422, "Cuenta inválida", "La cuenta no existe o no está activa"),
    CURRENCY_MISMATCH(422, "Monedas distintas", "Las cuentas y el monto deben estar en la misma moneda"),
    LIMIT_PER_TX_EXCEEDED(422, "Límite por operación excedido", "El monto supera el máximo por operación"),
    DAILY_LIMIT_EXCEEDED(422, "Límite diario excedido", "El acumulado diario supera el máximo permitido"),
    INSUFFICIENT_FUNDS(422, "Saldo insuficiente", "La cuenta no tiene saldo disponible para el monto"),
    FRAUD_REJECTED(422, "Operación rechazada", "La operación fue rechazada por antifraude"),
    RECIPIENT_NOT_SUPPORTED(422, "Destinatario de otro banco",
        "Por ahora solo puedes pagar a celulares de clientes PragBank"),
    RECIPIENT_TOKEN_EXPIRED(422, "Destinatario vencido", "Vuelve a buscar el celular; el token dura 5 minutos"),
    SELF_TRANSFER(422, "No puedes pagarte a ti mismo", "El celular destino es del mismo cliente que paga"),
    DEPENDENCY_UNAVAILABLE(503, "Servicio no disponible",
        "Una dependencia no responde; reintente con el mismo Idempotency-Key"),
    UNEXPECTED_ERROR(500, "Error interno", "Unexpected error");

    private final int httpStatus;
    private final String title;
    private final String defaultMessage;

    ErrorCatalog(int httpStatus, String title, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.title = title;
        this.defaultMessage = defaultMessage;
    }

    /** Code estable del contrato (igual al nombre de la constante). */
    public String code() {
        return name();
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String title() {
        return title;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /** Slug para el {@code type} del Problem (p. ej. amount-invalid). */
    public String slug() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<ErrorCatalog> fromCode(String code) {
        return Arrays.stream(values()).filter(e -> e.name().equals(code)).findFirst();
    }
}
