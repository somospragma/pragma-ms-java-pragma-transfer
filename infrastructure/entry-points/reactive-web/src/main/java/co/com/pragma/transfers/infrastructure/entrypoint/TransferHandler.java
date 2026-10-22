package co.com.pragma.transfers.infrastructure.entrypoint;

import co.com.pragma.transfers.infrastructure.entrypoint.dto.CreateTransferRequest;
import co.com.pragma.transfers.infrastructure.entrypoint.dto.TransferPageResponse;
import co.com.pragma.transfers.infrastructure.entrypoint.dto.TransferResponse;
import co.com.pragma.transfers.infrastructure.helpers.CorrelationWebFilter;
import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.model.transfer.TransferCommand;
import co.com.pragma.transfers.model.transfer.TransferResult;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import co.com.pragma.transfers.usecase.CreateTransferUseCase;
import co.com.pragma.transfers.usecase.GetTransferUseCase;
import co.com.pragma.transfers.usecase.ListAccountTransfersUseCase;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Handler de transferencias. Identidad del cliente: header X-Customer-Id (en el
 * taller reemplaza al JWT). Toda creación exige Idempotency-Key (UUID).
 */
@Component
public class TransferHandler {

    public static final String CUSTOMER_HEADER = "X-Customer-Id";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final Pattern ACCOUNT = Pattern.compile(CreateTransferRequest.ACCOUNT_PATTERN);
    private static final Pattern CUSTOMER = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final CreateTransferUseCase createTransfer;
    private final GetTransferUseCase getTransfer;
    private final ListAccountTransfersUseCase listTransfers;
    private final RequestValidator validator;

    public TransferHandler(CreateTransferUseCase createTransfer, GetTransferUseCase getTransfer,
                           ListAccountTransfersUseCase listTransfers, RequestValidator validator) {
        this.createTransfer = createTransfer;
        this.getTransfer = getTransfer;
        this.listTransfers = listTransfers;
        this.validator = validator;
    }

    public Mono<ServerResponse> create(ServerRequest request) {
        return Mono.defer(() -> {
            String customerId = customerId(request);
            String idempotencyKey = idempotencyKey(request);
            String correlationId = request.exchange().getAttributeOrDefault(CorrelationWebFilter.TRACE_ID, "");
            return request.bodyToMono(CreateTransferRequest.class)
                .switchIfEmpty(Mono.error(() -> validation("body", "es obligatorio")))
                .map(validator::validate)
                .map(body -> new TransferCommand(idempotencyKey, customerId, correlationId,
                    body.sourceAccountId(), body.targetAccountId(),
                    Money.parse(body.amount().value(), body.amount().currency()), body.description()))
                .flatMap(createTransfer::execute)
                .flatMap(TransferHandler::toResponse);
        });
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return Mono.defer(() -> {
            String customerId = customerId(request);
            String transferId = request.pathVariable("transferId");
            if (!isUuid(transferId)) {
                return Mono.error(new BusinessException(ErrorCatalog.TRANSFER_NOT_FOUND));
            }
            return getTransfer.execute(transferId, customerId)
                .flatMap(transfer -> ok(TransferResponse.from(transfer)));
        });
    }

    public Mono<ServerResponse> list(ServerRequest request) {
        return Mono.defer(() -> {
            String customerId = customerId(request);
            String accountId = request.pathVariable("accountId");
            if (!ACCOUNT.matcher(accountId).matches()) {
                return Mono.error(validation("accountId", "debe cumplir " + CreateTransferRequest.ACCOUNT_PATTERN));
            }
            int page = intParam(request, "page", 0);
            int size = intParam(request, "size", DEFAULT_PAGE_SIZE);
            TransferStatus status = request.queryParam("status").map(TransferHandler::status).orElse(null);
            LocalDate from = request.queryParam("from").map(v -> date("from", v)).orElse(null);
            LocalDate to = request.queryParam("to").map(v -> date("to", v)).orElse(null);
            return listTransfers.execute(accountId, customerId, status, from, to, page, size)
                .flatMap(result -> ok(TransferPageResponse.from(result)));
        });
    }

    private static Mono<ServerResponse> toResponse(TransferResult result) {
        TransferResponse body = TransferResponse.from(result.transfer());
        if (!result.created()) {
            return ok(body);
        }
        return ServerResponse.created(URI.create("/api/v1/transfers/" + body.transferId()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body);
    }

    private static Mono<ServerResponse> ok(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private static String customerId(ServerRequest request) {
        String customerId = request.headers().firstHeader(CUSTOMER_HEADER);
        if (customerId == null || customerId.isBlank()) {
            throw new BusinessException(ErrorCatalog.UNAUTHORIZED);
        }
        if (!CUSTOMER.matcher(customerId).matches()) {
            throw validation(CUSTOMER_HEADER, "formato inválido");
        }
        return customerId;
    }

    private static String idempotencyKey(ServerRequest request) {
        String key = request.headers().firstHeader(IDEMPOTENCY_HEADER);
        if (key == null || key.isBlank()) {
            throw validation(IDEMPOTENCY_HEADER, "es obligatorio");
        }
        if (!isUuid(key)) {
            throw validation(IDEMPOTENCY_HEADER, "debe ser un UUID");
        }
        return key.toLowerCase();
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int intParam(ServerRequest request, String name, int defaultValue) {
        Optional<String> raw = request.queryParam(name);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.get());
        } catch (NumberFormatException e) {
            throw validation(name, "debe ser entero");
        }
    }

    private static TransferStatus status(String raw) {
        try {
            return TransferStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw validation("status", "valor no permitido");
        }
    }

    private static LocalDate date(String name, String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw validation(name, "debe ser una fecha ISO 8601 (yyyy-MM-dd)");
        }
    }

    private static BusinessException validation(String field, String message) {
        return new BusinessException(ErrorCatalog.VALIDATION_ERROR, ErrorCatalog.VALIDATION_ERROR.defaultMessage(),
            Map.of(field, message));
    }
}
