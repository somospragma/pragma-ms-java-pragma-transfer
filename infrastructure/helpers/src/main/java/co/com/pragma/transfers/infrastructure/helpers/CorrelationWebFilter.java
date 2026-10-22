package co.com.pragma.transfers.infrastructure.helpers;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Correlacion de peticiones (OBS-O-002). Toma X-Correlation-Id (contrato v1),
 * o en su defecto X-Request-ID, o genera un UUID, y lo:
 *  - devuelve en los headers de respuesta X-Correlation-Id y X-Request-ID,
 *  - guarda como atributo del exchange (lectura sincrona, p.ej. el error handler),
 *  - propaga por Reactor Context (nunca ThreadLocal/MDC crudo, que no sobreviven
 *    los saltos de scheduler). Los adaptadores salientes lo leen de ahi.
 * Un valor con formato no seguro se descarta (evita inyeccion en logs/headers).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationWebFilter implements WebFilter {

    public static final String TRACE_ID = "traceId";
    public static final String HEADER = "X-Request-ID";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = resolve(exchange.getRequest().getHeaders().getFirst(CORRELATION_HEADER),
            exchange.getRequest().getHeaders().getFirst(HEADER));

        exchange.getResponse().getHeaders().set(CORRELATION_HEADER, traceId);
        exchange.getResponse().getHeaders().set(HEADER, traceId);
        exchange.getAttributes().put(TRACE_ID, traceId);

        return chain.filter(exchange)
            .contextWrite(Context.of(TRACE_ID, traceId))
            .doOnEach(signal -> {
                // Puente puntual a MDC para que los logs sincronos lleven el traceId.
                if (signal.isOnComplete() || signal.isOnError()) {
                    MDC.remove(TRACE_ID);
                }
            })
            .doFirst(() -> MDC.put(TRACE_ID, traceId));
    }

    static String resolve(String correlationId, String requestId) {
        if (isSafe(correlationId)) {
            return correlationId;
        }
        if (isSafe(requestId)) {
            return requestId;
        }
        return UUID.randomUUID().toString();
    }

    private static boolean isSafe(String value) {
        return value != null && SAFE.matcher(value).matches();
    }
}
