package co.com.pragma.transfers.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.exception.TechnicalException;
import co.com.pragma.transfers.model.phone.AliasBank;
import co.com.pragma.transfers.model.phone.PhoneNumber;
import co.com.pragma.transfers.model.phone.PhoneTransferPolicy;
import co.com.pragma.transfers.model.phone.RecipientAlias;
import co.com.pragma.transfers.model.phone.RecipientToken;
import co.com.pragma.transfers.ports.IAliasDirectoryGateway;
import co.com.pragma.transfers.ports.IRecipientTokenGateway;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ResolvePhoneRecipientUseCaseTest {

    private static final String CUSTOMER = "CUST-000123";
    private static final String PHONE = "+51987654321";
    private static final Instant NOW = Instant.parse("2026-10-15T15:04:05.123Z");

    @Mock
    private IAliasDirectoryGateway directory;
    @Mock
    private IRecipientTokenGateway tokens;

    private ResolvePhoneRecipientUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ResolvePhoneRecipientUseCase(directory, tokens,
            new PhoneTransferPolicy(new BigDecimal("500.00"), "PEN", Duration.ofMinutes(5)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Predicate<Throwable> code(ErrorCatalog error) {
        return e -> e instanceof BusinessException be && be.code().equals(error.code());
    }

    private static RecipientAlias alias(AliasBank bank, String account, boolean active) {
        return new RecipientAlias(PhoneNumber.parse(PHONE), bank, account, "Mario Quiroz", active);
    }

    @Test
    void pragbankPhoneIssuesSingleUseTokenWithMaskedNameAndFiveMinutes() {
        when(directory.resolve(PhoneNumber.parse(PHONE)))
            .thenReturn(Mono.just(alias(AliasBank.PRAGBANK, "191-0007654321", true)));
        when(tokens.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(useCase.execute(PHONE, CUSTOMER))
            .assertNext(token -> {
                assertThat(token.token()).startsWith(RecipientToken.PREFIX).hasSize(25);
                assertThat(token.maskedName()).isEqualTo("Mar*** Qui***");
                assertThat(token.customerId()).isEqualTo(CUSTOMER);
                assertThat(token.targetAccountId()).isEqualTo("191-0007654321");
                assertThat(token.targetPhoneMasked()).isEqualTo("+51 9** *** 321");
                assertThat(token.createdAt()).isEqualTo(NOW);
                assertThat(token.expiresAt()).isEqualTo(Instant.parse("2026-10-15T15:09:05Z"));
                assertThat(token.isUsed()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void tokensAreUnpredictable() {
        assertThat(ResolvePhoneRecipientUseCase.newToken()).isNotEqualTo(ResolvePhoneRecipientUseCase.newToken());
    }

    @Test
    void invalidPhoneIsRejectedWithoutCallingTheDirectory() {
        StepVerifier.create(useCase.execute("987654321", CUSTOMER))
            .verifyErrorMatches(code(ErrorCatalog.PHONE_INVALID));
        StepVerifier.create(useCase.execute(null, CUSTOMER))
            .verifyErrorMatches(code(ErrorCatalog.PHONE_INVALID));
        verifyNoInteractions(directory, tokens);
    }

    @Test
    void notAffiliatedPhoneIs404() {
        when(directory.resolve(any())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(PHONE, CUSTOMER))
            .verifyErrorMatches(code(ErrorCatalog.RECIPIENT_NOT_FOUND));
        verify(tokens, never()).save(any());
    }

    @Test
    void otherBankIsNotSupported() {
        when(directory.resolve(any())).thenReturn(Mono.just(alias(AliasBank.OTHER, null, true)));
        StepVerifier.create(useCase.execute(PHONE, CUSTOMER))
            .verifyErrorMatches(code(ErrorCatalog.RECIPIENT_NOT_SUPPORTED));
        verify(tokens, never()).save(any());
    }

    @Test
    void suspendedPragbankAliasIsTreatedAsNotFound() {
        when(directory.resolve(any())).thenReturn(Mono.just(alias(AliasBank.PRAGBANK, "191-0007654321", false)));
        StepVerifier.create(useCase.execute(PHONE, CUSTOMER))
            .verifyErrorMatches(code(ErrorCatalog.RECIPIENT_NOT_FOUND));
    }

    @Test
    void directoryDownPropagatesTechnicalError() {
        when(directory.resolve(any())).thenReturn(Mono.error(new TechnicalException(
            ErrorCatalog.DEPENDENCY_UNAVAILABLE, "directorio no disponible", null)));
        StepVerifier.create(useCase.execute(PHONE, CUSTOMER))
            .verifyErrorMatches(e -> e instanceof TechnicalException);
    }
}
