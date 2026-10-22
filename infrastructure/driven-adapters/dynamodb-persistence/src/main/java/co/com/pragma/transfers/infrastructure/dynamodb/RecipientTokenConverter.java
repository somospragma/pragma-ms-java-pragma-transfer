package co.com.pragma.transfers.infrastructure.dynamodb;

import co.com.pragma.transfers.infrastructure.dynamodb.item.RecipientTokenItem;
import co.com.pragma.transfers.model.phone.RecipientToken;
import java.time.Instant;

/** Conversión entre RecipientToken y el ítem de la tabla recipient-tokens. */
final class RecipientTokenConverter {

    private RecipientTokenConverter() {
    }

    static RecipientTokenItem toItem(RecipientToken token) {
        RecipientTokenItem item = new RecipientTokenItem();
        item.setRecipientToken(token.token());
        item.setCustomerId(token.customerId());
        item.setTargetAccountId(token.targetAccountId());
        item.setTargetPhoneMasked(token.targetPhoneMasked());
        item.setMaskedName(token.maskedName());
        item.setCreatedAt(token.createdAt().toString());
        item.setExpiresAt(token.expiresAt().getEpochSecond());
        item.setUsedAt(token.usedAt() == null ? null : token.usedAt().toString());
        item.setUsedByTransferId(token.usedByTransferId());
        return item;
    }

    static RecipientToken toDomain(RecipientTokenItem item) {
        return new RecipientToken(item.getRecipientToken(), item.getCustomerId(), item.getTargetAccountId(),
            item.getTargetPhoneMasked(), item.getMaskedName(), Instant.parse(item.getCreatedAt()),
            Instant.ofEpochSecond(item.getExpiresAt()),
            item.getUsedAt() == null ? null : Instant.parse(item.getUsedAt()), item.getUsedByTransferId());
    }
}
