package co.com.pragma.transfers.infrastructure.entrypoint;

import co.com.pragma.transfers.infrastructure.entrypoint.dto.CreatePhoneTransferRequest;
import co.com.pragma.transfers.infrastructure.entrypoint.dto.RecipientResolutionResponse;
import co.com.pragma.transfers.infrastructure.entrypoint.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springdoc.core.annotations.RouterOperation;
import org.springdoc.core.annotations.RouterOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Rutas de pago a celular del contrato openapi/ms-transfers-v3.yaml (HU-03).
 * Documentadas con @RouterOperation para que springdoc las incluya en /v3/api-docs.
 */
@Configuration
public class PhoneTransferRouter {

    private static final String PROBLEM = "application/problem+json";
    private static final String TAG = "Pago a celular";

    @Bean
    @RouterOperations({
        @RouterOperation(path = "/api/v1/phone-transfers/recipients", method = RequestMethod.GET,
            beanClass = PhoneTransferHandler.class, beanMethod = "resolveRecipient",
            operation = @Operation(operationId = "resolvePhoneRecipient", tags = TAG,
                summary = "Resolver destinatario por celular",
                description = "Reglas RN-09 y RN-10. Devuelve un recipientToken de un solo uso que vence en 5 minutos "
                    + "(RN-12). La cuenta destino y el nombre completo nunca se exponen. En el query string el '+' "
                    + "se envía como %2B.",
                parameters = {
                    @Parameter(in = ParameterIn.QUERY, name = "phone", required = true,
                        description = "Celular peruano en formato E.164 (^\\+519\\d{8}$)", example = "+51987654321"),
                    @Parameter(in = ParameterIn.HEADER, name = TransferHandler.CUSTOMER_HEADER, required = true,
                        description = "Cliente que hace la operación", example = "CUST-000123"),
                    @Parameter(in = ParameterIn.HEADER, name = "X-Correlation-Id",
                        description = "Se genera si no llega")},
                responses = {
                    @ApiResponse(responseCode = "200", description = "Destinatario resuelto (nombre enmascarado y token)",
                        content = @Content(schema = @Schema(implementation = RecipientResolutionResponse.class))),
                    @ApiResponse(responseCode = "400", description = "PHONE_INVALID",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "401", description = "Falta X-Customer-Id",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "404", description = "RECIPIENT_NOT_FOUND: el celular no está afiliado",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "RECIPIENT_NOT_SUPPORTED: celular de otro banco",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "503", description = "DEPENDENCY_UNAVAILABLE: el directorio no responde",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))})),
        @RouterOperation(path = "/api/v1/phone-transfers", method = RequestMethod.POST,
            beanClass = PhoneTransferHandler.class, beanMethod = "create",
            operation = @Operation(operationId = "createPhoneTransfer", tags = TAG,
                summary = "Ejecutar pago a celular",
                description = "Reglas RN-11, RN-12 y RN-13, más RN-03 y RN-05 a RN-08 de la v1. Reutiliza el flujo de "
                    + "débito, crédito y compensación de createTransfer con channel PHONE.",
                parameters = {
                    @Parameter(in = ParameterIn.HEADER, name = TransferHandler.CUSTOMER_HEADER, required = true,
                        description = "Cliente que hace la operación", example = "CUST-000123"),
                    @Parameter(in = ParameterIn.HEADER, name = TransferHandler.IDEMPOTENCY_HEADER, required = true,
                        description = "UUID generado por el cliente; se conserva 24 horas",
                        example = "5d6e7f80-1a2b-4c3d-9e8f-7a6b5c4d3e2f"),
                    @Parameter(in = ParameterIn.HEADER, name = "X-Correlation-Id",
                        description = "Se genera si no llega")},
                requestBody = @RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = CreatePhoneTransferRequest.class))),
                responses = {
                    @ApiResponse(responseCode = "201", description = "Pago creado (channel PHONE con targetPhoneMasked)",
                        content = @Content(schema = @Schema(implementation = TransferResponse.class))),
                    @ApiResponse(responseCode = "200", description = "Reintento idempotente: pago original",
                        content = @Content(schema = @Schema(implementation = TransferResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Validación (VALIDATION_ERROR, AMOUNT_INVALID)",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "401", description = "Falta X-Customer-Id",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "403", description = "La cuenta origen no es del cliente (ACCOUNT_NOT_OWNED)",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_KEY_REUSED",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "RECIPIENT_TOKEN_EXPIRED, SELF_TRANSFER, "
                        + "LIMIT_PER_TX_EXCEEDED (más de S/ 500.00 o no PEN) o reglas de la v1",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "503", description = "DEPENDENCY_UNAVAILABLE",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))}))
    })
    public RouterFunction<ServerResponse> phoneTransferRoutes(PhoneTransferHandler handler) {
        return RouterFunctions.route()
            .GET("/api/v1/phone-transfers/recipients", handler::resolveRecipient)
            .POST("/api/v1/phone-transfers", handler::create)
            .build();
    }
}
