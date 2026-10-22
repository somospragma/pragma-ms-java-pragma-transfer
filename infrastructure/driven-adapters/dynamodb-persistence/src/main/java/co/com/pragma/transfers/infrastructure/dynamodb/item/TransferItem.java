package co.com.pragma.transfers.infrastructure.dynamodb.item;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * Ítem de la tabla transfers. PK transferId.
 * <ul>
 *   <li>GSI {@value #GSI_SOURCE_ACCOUNT}: sourceAccountId + createdAt (historial por cuenta).</li>
 *   <li>GSI {@value #GSI_CUSTOMER_DAY}: customerDay (customerId#yyyy-MM-dd) + createdAt (límite diario RN-05).</li>
 * </ul>
 * Montos como string decimal; fechas ISO-8601 UTC (ordenan lexicográficamente).
 */
@DynamoDbBean
public class TransferItem {

    public static final String GSI_SOURCE_ACCOUNT = "sourceAccountId-createdAt-index";
    public static final String GSI_CUSTOMER_DAY = "customerDay-createdAt-index";

    private String transferId;
    private String idempotencyKey;
    private String customerId;
    private String sourceAccountId;
    private String targetAccountId;
    private String amount;
    private String currency;
    private String description;
    private String status;
    private String channel;
    private String failureCode;
    private Integer fraudScore;
    private String debitPostingId;
    private String creditPostingId;
    private String correlationId;
    private String businessDate;
    private String customerDay;
    private String createdAt;
    private String updatedAt;

    @DynamoDbPartitionKey
    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = GSI_SOURCE_ACCOUNT)
    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public void setSourceAccountId(String sourceAccountId) {
        this.sourceAccountId = sourceAccountId;
    }

    public String getTargetAccountId() {
        return targetAccountId;
    }

    public void setTargetAccountId(String targetAccountId) {
        this.targetAccountId = targetAccountId;
    }

    public String getAmount() {
        return amount;
    }

    public void setAmount(String amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    public Integer getFraudScore() {
        return fraudScore;
    }

    public void setFraudScore(Integer fraudScore) {
        this.fraudScore = fraudScore;
    }

    public String getDebitPostingId() {
        return debitPostingId;
    }

    public void setDebitPostingId(String debitPostingId) {
        this.debitPostingId = debitPostingId;
    }

    public String getCreditPostingId() {
        return creditPostingId;
    }

    public void setCreditPostingId(String creditPostingId) {
        this.creditPostingId = creditPostingId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(String businessDate) {
        this.businessDate = businessDate;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = GSI_CUSTOMER_DAY)
    public String getCustomerDay() {
        return customerDay;
    }

    public void setCustomerDay(String customerDay) {
        this.customerDay = customerDay;
    }

    @DynamoDbSecondarySortKey(indexNames = {GSI_SOURCE_ACCOUNT, GSI_CUSTOMER_DAY})
    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
