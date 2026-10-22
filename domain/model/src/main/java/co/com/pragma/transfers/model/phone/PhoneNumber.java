package co.com.pragma.transfers.model.phone;

import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Celular peruano en formato E.164 (RN-09): {@code +51} seguido de 9 dígitos que
 * empiezan en 9. Es dato personal: fuera del servicio solo viaja enmascarado
 * ({@link #masked()}).
 */
public record PhoneNumber(String value) {

    public static final String PATTERN = "^\\+519\\d{8}$";
    private static final Pattern FORMAT = Pattern.compile(PATTERN);
    private static final String FIELD = "phone";

    public PhoneNumber {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw invalid(value == null || value.isBlank() ? "es obligatorio" : "debe cumplir " + PATTERN);
        }
    }

    /** RN-09: construye el celular o falla con 400 PHONE_INVALID. */
    public static PhoneNumber parse(String value) {
        return new PhoneNumber(value);
    }

    /** Formato del contrato: {@code +51 9** *** 321} (solo el primer y los tres últimos dígitos). */
    public String masked() {
        return "+51 " + value.charAt(3) + "** *** " + value.substring(value.length() - 3);
    }

    @Override
    public String toString() {
        return masked();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCatalog.PHONE_INVALID, ErrorCatalog.PHONE_INVALID.defaultMessage(),
            Map.of(FIELD, message));
    }
}
