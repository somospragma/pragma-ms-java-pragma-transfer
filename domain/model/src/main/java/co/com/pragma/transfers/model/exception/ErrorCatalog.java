package co.com.pragma.transfers.model.exception;

/**
 * Catalogo versionado de codigos de negocio (ERR-BP-008). Los consumidores
 * reaccionan por code, no por el texto del mensaje. Estable entre versiones.
 * Los codigos de las reglas RN-01 a RN-08 se agregan en la HU-01.
 */
public enum ErrorCatalog {

    VALIDATION_ERROR("TRF-400-001", "Request validation failed"),
    DEPENDENCY_UNAVAILABLE("TRF-503-001", "Dependency unavailable"),
    UNEXPECTED_ERROR("TRF-500-001", "Unexpected error");

    private final String code;
    private final String defaultMessage;

    ErrorCatalog(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
