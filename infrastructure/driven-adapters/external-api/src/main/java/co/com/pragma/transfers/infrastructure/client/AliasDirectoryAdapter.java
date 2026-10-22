package co.com.pragma.transfers.infrastructure.client;

import co.com.pragma.transfers.infrastructure.client.dto.AliasDto;
import co.com.pragma.transfers.model.phone.AliasBank;
import co.com.pragma.transfers.model.phone.PhoneNumber;
import co.com.pragma.transfers.model.phone.RecipientAlias;
import co.com.pragma.transfers.ports.IAliasDirectoryGateway;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Adaptador del directorio de alias por celular (openapi/alias-directory.yaml).
 * Es una lectura: se protege con timeout, circuit breaker y retry. 404 significa
 * "no afiliado" (vacío); cualquier otra falla es DEPENDENCY_UNAVAILABLE.
 */
public class AliasDirectoryAdapter implements IAliasDirectoryGateway {

    private static final String PATH = "/directory/v1/aliases/";

    private final WebClient client;
    private final OutboundCalls calls;

    public AliasDirectoryAdapter(WebClient directoryWebClient, OutboundCalls calls) {
        this.client = directoryWebClient;
        this.calls = calls;
    }

    @Override
    public Mono<RecipientAlias> resolve(PhoneNumber phone) {
        // El celular ya está validado (^\+519\d{8}$): se concatena sin plantilla para que el '+'
        // viaje literal en el path (como lo define el directorio) y no como %2B.
        return calls.read(correlationId -> client.get()
                .uri(PATH + phone.value())
                .headers(OutboundCalls.correlation(correlationId))
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(AliasDto.class);
                    }
                    if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        return response.releaseBody().then(Mono.<AliasDto>empty());
                    }
                    return response.releaseBody().then(Mono.error(calls.unavailable(response.statusCode().value())));
                }))
            .map(dto -> toAlias(phone, dto));
    }

    static RecipientAlias toAlias(PhoneNumber phone, AliasDto dto) {
        AliasBank bank = "PRAGBANK".equals(dto.bank()) ? AliasBank.PRAGBANK : AliasBank.OTHER;
        return new RecipientAlias(phone, bank, dto.accountId(), dto.holderName(), "ACTIVE".equals(dto.status()));
    }
}
