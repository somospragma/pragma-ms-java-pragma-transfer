package co.com.pragma.transfers.infrastructure.dynamodb.item;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * Ítem de la tabla idempotency. PK idempotencyKey; {@code expiresAt} (epoch en
 * segundos) es el atributo TTL de DynamoDB (24 h).
 */
@DynamoDbBean
public class IdempotencyItem {

    public static final String TTL_ATTRIBUTE = "expiresAt";

    private String idempotencyKey;
    private String requestHash;
    private String transferId;
    private String createdAt;
    private Long expiresAt;

    @DynamoDbPartitionKey
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
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
}
