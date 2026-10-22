package co.com.pragma.transfers.infrastructure.entrypoint;

import co.com.pragma.transfers.infrastructure.helpers.CorrelationWebFilter;
import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.exception.TechnicalException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manejo de errores centralizado en formato RFC 9457 / 7807 (Problem Details)
 * con las extensiones del contrato v1: {@code code}, {@code correlationId} y
 * {@code errors[{field, message}]}.
 *
 * - El status HTTP y el titulo salen de {@link ErrorCatalog} (codes del contrato).
 * - Implementa WebExceptionHandler + @Order(-2), NO @RestControllerAdvice.
 * - Separa errores de NEGOCIO (4xx) de TECNICOS (5xx): estos nunca exponen
 *   internals; 503 DEPENDENCY_UNAVAILABLE agrega Retry-After.
 * - Jackson 3 (tools.jackson.*), default de Spring Boot 4.
 */
@Order(-2)
public class GlobalErrorWebExceptionHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorWebExceptionHandler.class);
    static final String TYPE_BASE = "https://api.pragbank.pe/errors/";
    static final String RETRY_AFTER_SECONDS = "5";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        Map<String, Object> problem = toProblem(ex, exchange);
        int status = (int) problem.get("status");
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            exchange.getResponse().getHeaders().set("Retry-After", RETRY_AFTER_SECONDS);
        }

        byte[] bytes = jsonMapper.writeValueAsBytes(problem);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private Map<String, Object> toProblem(Throwable error, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String correlationId = exchange.getAttributeOrDefault(CorrelationWebFilter.TRACE_ID, "");

        if (error instanceof BusinessException be) {
            ErrorCatalog entry = ErrorCatalog.fromCode(be.code()).orElse(null);
            int status = entry == null ? HttpStatus.UNPROCESSABLE_ENTITY.value() : entry.httpStatus();
            String title = entry == null ? "Regla de negocio incumplida" : entry.title();
            return problem(status, title, be.getMessage(), path, be.code(), be.metadata(), correlationId);
        }
        if (error instanceof WebExchangeBindException bind) {
            Map<String, Object> fields = new LinkedHashMap<>();
            bind.getFieldErrors().forEach(fe -> fields.put(fe.getField(), fe.getDefaultMessage()));
            return catalog(ErrorCatalog.VALIDATION_ERROR, null, path, fields, correlationId);
        }
        if (error instanceof ServerWebInputException) {
            // Cuerpo ilegible, campos desconocidos o tipos incorrectos: no se expone el detalle del parser.
            return catalog(ErrorCatalog.VALIDATION_ERROR, "El cuerpo de la petición no es válido", path,
                Map.of(), correlationId);
        }
        if (error instanceof ResponseStatusException rse) {
            return fromStatus(rse, path, correlationId);
        }
        if (error instanceof IllegalArgumentException iae) {
            return catalog(ErrorCatalog.VALIDATION_ERROR, iae.getMessage(), path, Map.of(), correlationId);
        }
        // Errores tecnicos y no controlados: NUNCA exponer internals al cliente.
        if (error instanceof TechnicalException te) {
            ErrorCatalog entry = ErrorCatalog.fromCode(te.code())
                .filter(e -> e.httpStatus() >= 500)
                .orElse(ErrorCatalog.UNEXPECTED_ERROR);
            log.error("Technical error [{}] correlationId={}", te.code(), correlationId, te);
            return catalog(entry, null, path, Map.of(), correlationId);
        }
        log.error("Unhandled error correlationId={}", correlationId, error);
        return catalog(ErrorCatalog.UNEXPECTED_ERROR, null, path, Map.of(), correlationId);
    }

    private Map<String, Object> fromStatus(ResponseStatusException rse, String path, String correlationId) {
        int status = rse.getStatusCode().value();
        ErrorCatalog entry;
        if (status == HttpStatus.NOT_FOUND.value()) {
            entry = ErrorCatalog.ROUTE_NOT_FOUND;
        } else if (status >= 500) {
            entry = ErrorCatalog.UNEXPECTED_ERROR;
        } else {
            entry = ErrorCatalog.VALIDATION_ERROR;
        }
        String detail = status >= 500 ? null : rse.getReason();
        return problem(status, entry.title(), detail == null ? entry.defaultMessage() : detail, path,
            entry.code(), Map.of(), correlationId);
    }

    private Map<String, Object> catalog(ErrorCatalog entry, String detail, String path,
                                        Map<String, Object> fields, String correlationId) {
        return problem(entry.httpStatus(), entry.title(), detail == null ? entry.defaultMessage() : detail,
            path, entry.code(), fields, correlationId);
    }

    private Map<String, Object> problem(int status, String title, String detail, String path, String code,
                                        Map<String, Object> fields, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", TYPE_BASE + ErrorCatalog.fromCode(code).map(ErrorCatalog::slug).orElse("business-rule"));
        body.put("title", title);
        body.put("status", status);
        // Extensiones del contrato (mecanismo de extension del propio RFC):
        body.put("code", code);
        body.put("detail", detail == null ? title : detail);
        body.put("instance", path);
        if (!correlationId.isBlank()) {
            body.put("correlationId", correlationId);
        }
        if (fields != null && !fields.isEmpty()) {
            List<Map<String, Object>> errors = fields.entrySet().stream()
                .map(e -> Map.<String, Object>of("field", e.getKey(), "message", String.valueOf(e.getValue())))
                .toList();
            body.put("errors", errors);
        }
        return body;
    }
}
