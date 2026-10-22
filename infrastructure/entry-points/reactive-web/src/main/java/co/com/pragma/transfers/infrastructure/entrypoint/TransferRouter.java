package co.com.pragma.transfers.infrastructure.entrypoint;

import co.com.pragma.transfers.infrastructure.entrypoint.dto.CreateTransferRequest;
import co.com.pragma.transfers.infrastructure.entrypoint.dto.TransferPageResponse;
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
 * Rutas del contrato openapi/ms-transfers-v1.yaml (WebFlux funcional).
 * Las anotaciones @RouterOperation permiten que springdoc genere la especificación
 * en /v3/api-docs a partir del código (las rutas funcionales no se descubren solas).
 */
@Configuration
public class TransferRouter {

    private static final String PROBLEM = "application/problem+json";
    private static final String TAG = "Transferencias";

    @Bean
    @RouterOperations({
        @RouterOperation(path = "/api/v1/transfers", method = RequestMethod.POST,
            beanClass = TransferHandler.class, beanMethod = "create",
            operation = @Operation(operationId = "createTransfer", tags = TAG,
                summary = "Crear transferencia entre cuentas PragBank",
                description = "Aplica RN-01 a RN-08. Misma Idempotency-Key y mismo cuerpo: 200 con la transferencia original. "
                    + "Misma llave con otro cuerpo: 409 IDEMPOTENCY_KEY_REUSED.",
                parameters = {
                    @Parameter(in = ParameterIn.HEADER, name = TransferHandler.CUSTOMER_HEADER, required = true,
                        description = "Cliente que hace la operación", example = "CUST-000123"),
                    @Parameter(in = ParameterIn.HEADER, name = TransferHandler.IDEMPOTENCY_HEADER, required = true,
                        description = "UUID generado por el cliente; se conserva 24 horas",
                        example = "3b1f6c2a-9d4e-4f7a-8b21-5c6d7e8f9a01"),
                    @Parameter(in = ParameterIn.HEADER, name = "X-Correlation-Id",
                        description = "Se genera si no llega")},
                requestBody = @RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = CreateTransferRequest.class))),
                responses = {
                    @ApiResponse(responseCode = "201", description = "Transferencia creada (COMPLETED, PENDING_REVIEW o COMPENSATED)",
                        content = @Content(schema = @Schema(implementation = TransferResponse.class))),
                    @ApiResponse(responseCode = "200", description = "Reintento idempotente: transferencia original",
                        content = @Content(schema = @Schema(implementation = TransferResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Validación (VALIDATION_ERROR, AMOUNT_INVALID)",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "401", description = "Falta X-Customer-Id",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "403", description = "La cuenta origen no es del cliente (ACCOUNT_NOT_OWNED)",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_KEY_REUSED",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "Regla de negocio (límites, saldo, moneda, cuenta, fraude)",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "503", description = "DEPENDENCY_UNAVAILABLE",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))})),
        @RouterOperation(path = "/api/v1/transfers/{transferId}", method = RequestMethod.GET,
            beanClass = TransferHandler.class, beanMethod = "get",
            operation = @Operation(operationId = "getTransfer", tags = TAG, summary = "Consultar una transferencia",
                parameters = {
                    @Parameter(in = ParameterIn.PATH, name = "transferId", required = true, description = "UUID de la transferencia"),
                    @Parameter(in = ParameterIn.HEADER, name = TransferHandler.CUSTOMER_HEADER, required = true, example = "CUST-000123")},
                responses = {
                    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TransferResponse.class))),
                    @ApiResponse(responseCode = "403", description = "La transferencia no es del cliente",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "404", description = "TRANSFER_NOT_FOUND",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))})),
        @RouterOperation(path = "/api/v1/accounts/{accountId}/transfers", method = RequestMethod.GET,
            beanClass = TransferHandler.class, beanMethod = "list",
            operation = @Operation(operationId = "listAccountTransfers", tags = TAG,
                summary = "Historial de transferencias de una cuenta (más reciente primero)",
                parameters = {
                    @Parameter(in = ParameterIn.PATH, name = "accountId", required = true, example = "191-0001234567"),
                    @Parameter(in = ParameterIn.HEADER, name = TransferHandler.CUSTOMER_HEADER, required = true, example = "CUST-000123"),
                    @Parameter(in = ParameterIn.QUERY, name = "page", description = "Página (desde 0)", example = "0"),
                    @Parameter(in = ParameterIn.QUERY, name = "size", description = "Tamaño de página", example = "20"),
                    @Parameter(in = ParameterIn.QUERY, name = "status", description = "Filtro por estado"),
                    @Parameter(in = ParameterIn.QUERY, name = "from", description = "Desde (yyyy-MM-dd)"),
                    @Parameter(in = ParameterIn.QUERY, name = "to", description = "Hasta (yyyy-MM-dd)")},
                responses = {
                    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TransferPageResponse.class))),
                    @ApiResponse(responseCode = "403", description = "La cuenta no es del cliente",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))}))
    })
    public RouterFunction<ServerResponse> transferRoutes(TransferHandler handler) {
        return RouterFunctions.route()
            .POST("/api/v1/transfers", handler::create)
            .GET("/api/v1/transfers/{transferId}", handler::get)
            .GET("/api/v1/accounts/{accountId}/transfers", handler::list)
            .build();
    }
}
