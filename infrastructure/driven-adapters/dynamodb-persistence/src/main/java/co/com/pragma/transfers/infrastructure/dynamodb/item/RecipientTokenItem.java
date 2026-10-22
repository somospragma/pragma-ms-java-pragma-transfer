package co.com.pragma.transfers.infrastructure.dynamodb.item;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * Ítem de la tabla recipient-tokens (HU-03). PK {@code recipientToken};
 * {@code expiresAt} (epoch en segundos) es el atributo TTL de DynamoDB (5 min).
 * {@code usedAt} y {@code usedByTransferId} se escriben con una actualización
 * condicional (uso único). No guarda el celular completo ni el nombre del titular.
 */
@DynamoDbBean
public class RecipientTokenItem {

    public static final String TTL_ATTRIBUTE = "expiresAt";

    private String recipientToken;
    private String customerId;
    private String targetAccountId;
    private String targetPhoneMasked;
    private String maskedName;
    private String createdAt;
    private Long expiresAt;
    private String usedAt;
    private String usedByTransferId;

    @DynamoDbPartitionKey
    public String getRecipientToken() {
        return recipientToken;
    }

    public void setRecipientToken(String recipientToken) {
        this.recipientToken = recipientToken;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getTargetAccountId() {
        return targetAccountId;
    }

    public void setTargetAccountId(String targetAccountId) {
        this.targetAccountId = targetAccountId;
    }

    public String getTargetPhoneMasked() {
        return targetPhoneMasked;
    }

    public void setTargetPhoneMasked(String targetPhoneMasked) {
        this.targetPhoneMasked = targetPhoneMasked;
    }

    public String getMaskedName() {
        return maskedName;
    }

    public void setMaskedName(String maskedName) {
        this.maskedName = maskedName;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(String usedAt) {
        this.usedAt = usedAt;
    }

    public String getUsedByTransferId() {
        return usedByTransferId;
    }

    public void setUsedByTransferId(String usedByTransferId) {
        this.usedByTransferId = usedByTransferId;
    }
}
