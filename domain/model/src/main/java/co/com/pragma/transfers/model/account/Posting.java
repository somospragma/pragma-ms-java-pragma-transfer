package co.com.pragma.transfers.model.account;

/** Movimiento registrado en el core (débito, crédito o reversa). */
public record Posting(String postingId, String operationId, String type, String status) {
}
