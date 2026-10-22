package co.com.pragma.transfers.ports;

import co.com.pragma.transfers.model.phone.PhoneNumber;
import co.com.pragma.transfers.model.phone.RecipientAlias;
import reactor.core.publisher.Mono;

/**
 * Directorio de alias por celular (RN-10). Errores: TechnicalException
 * DEPENDENCY_UNAVAILABLE si no responde.
 */
public interface IAliasDirectoryGateway {

    /** @return vacío si el celular no está afiliado. */
    Mono<RecipientAlias> resolve(PhoneNumber phone);
}
