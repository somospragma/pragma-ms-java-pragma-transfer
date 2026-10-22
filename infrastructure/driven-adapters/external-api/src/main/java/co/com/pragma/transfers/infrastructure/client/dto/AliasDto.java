package co.com.pragma.transfers.infrastructure.client.dto;

/** Alias de openapi/alias-directory.yaml. {@code accountId} y {@code holderName} solo vienen si bank es PRAGBANK. */
public record AliasDto(String phone, String bank, String accountId, String holderName, String status) {
}
