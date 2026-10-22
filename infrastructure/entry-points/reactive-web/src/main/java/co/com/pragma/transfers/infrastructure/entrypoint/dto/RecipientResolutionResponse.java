package co.com.pragma.transfers.infrastructure.entrypoint.dto;

import co.com.pragma.transfers.model.phone.RecipientToken;

/** RecipientResolution del contrato v3: sin cuenta destino ni nombre completo. */
public record RecipientResolutionResponse(String recipientToken, String maskedName, String bank,
                                          String expiresAt) {

    static final String PRAGBANK = "PRAGBANK";

    public static RecipientResolutionResponse from(RecipientToken token) {
        return new RecipientResolutionResponse(token.token(), token.maskedName(), PRAGBANK,
            token.expiresAt().toString());
    }
}
