package co.com.pragma.transfers.usecase;

import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.phone.AliasBank;
import co.com.pragma.transfers.model.phone.PhoneNumber;
import co.com.pragma.transfers.model.phone.PhoneTransferPolicy;
import co.com.pragma.transfers.model.phone.RecipientAlias;
import co.com.pragma.transfers.model.phone.RecipientToken;
import co.com.pragma.transfers.ports.IAliasDirectoryGateway;
import co.com.pragma.transfers.ports.IRecipientTokenGateway;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import reactor.core.publisher.Mono;

/**
 * GET /api/v1/phone-transfers/recipients. Aplica RN-09 (formato), RN-10
 * (directorio: no afiliado → 404, otro banco → 422) y emite un recipientToken de
 * un solo uso que vence en 5 minutos (RN-12). La cuenta destino y el nombre
 * completo se quedan del lado del servidor: el cliente solo recibe el nombre
 * enmascarado y el token.
 */
public class ResolvePhoneRecipientUseCase {

    private static final int TOKEN_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IAliasDirectoryGateway directory;
    private final IRecipientTokenGateway tokens;
    private final PhoneTransferPolicy policy;
    private final Clock clock;

    public ResolvePhoneRecipientUseCase(IAliasDirectoryGateway directory, IRecipientTokenGateway tokens,
                                        PhoneTransferPolicy policy, Clock clock) {
        this.directory = directory;
        this.tokens = tokens;
        this.policy = policy;
        this.clock = clock;
    }

    public Mono<RecipientToken> execute(String phone, String customerId) {
        return Mono.defer(() -> {
            PhoneNumber number = PhoneNumber.parse(phone);
            return directory.resolve(number)
                .switchIfEmpty(Mono.error(() -> new BusinessException(ErrorCatalog.RECIPIENT_NOT_FOUND)))
                .flatMap(alias -> issue(alias, number, customerId));
        });
    }

    private Mono<RecipientToken> issue(RecipientAlias alias, PhoneNumber number, String customerId) {
        if (alias.bank() != AliasBank.PRAGBANK) {
            return Mono.error(new BusinessException(ErrorCatalog.RECIPIENT_NOT_SUPPORTED));
        }
        if (!alias.isPayable()) {
            // PragBank pero suspendido o sin cuenta asociada: para el cliente no está afiliado.
            return Mono.error(new BusinessException(ErrorCatalog.RECIPIENT_NOT_FOUND));
        }
        Instant now = clock.instant();
        // Segundos exactos: es la misma precisión del atributo TTL de DynamoDB.
        Instant expiresAt = now.plus(policy.tokenTtl()).truncatedTo(ChronoUnit.SECONDS);
        return tokens.save(new RecipientToken(newToken(), customerId, alias.accountId(), number.masked(),
            alias.maskedName(), now, expiresAt, null, null));
    }

    /** Token opaco e impredecible (128 bits), seguro para URL: {@code rt_} + base64url. */
    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return RecipientToken.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
