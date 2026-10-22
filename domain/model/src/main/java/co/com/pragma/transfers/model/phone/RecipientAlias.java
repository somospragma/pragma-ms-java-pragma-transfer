package co.com.pragma.transfers.model.phone;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Resultado del directorio de alias para un celular. {@code accountId} y
 * {@code holderName} solo vienen cuando el banco es PragBank y nunca se exponen
 * al cliente: la respuesta lleva el nombre enmascarado y un token.
 */
public record RecipientAlias(PhoneNumber phone, AliasBank bank, String accountId, String holderName,
                             boolean active) {

    private static final int VISIBLE_CHARS = 3;

    /** RN-10: solo se puede pagar a celulares activos de clientes PragBank con cuenta asociada. */
    public boolean isPayable() {
        return bank == AliasBank.PRAGBANK && active && accountId != null && !accountId.isBlank();
    }

    /** Nombre enmascarado: primeras 3 letras de cada palabra + {@code ***} ("Mario Quiroz" → "Mar*** Qui***"). */
    public String maskedName() {
        if (holderName == null || holderName.isBlank()) {
            return "***";
        }
        return Arrays.stream(holderName.trim().split("\\s+"))
            .map(word -> word.substring(0, Math.min(VISIBLE_CHARS, word.length())) + "***")
            .collect(Collectors.joining(" "));
    }

    @Override
    public String toString() {
        return "RecipientAlias[phone=" + phone.masked() + ", bank=" + bank + ", holder="
            + maskedName() + "]";
    }
}
