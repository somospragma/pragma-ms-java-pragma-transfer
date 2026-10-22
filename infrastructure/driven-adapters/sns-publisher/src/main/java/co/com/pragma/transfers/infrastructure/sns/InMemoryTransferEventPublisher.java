package co.com.pragma.transfers.infrastructure.sns;

import co.com.pragma.transfers.model.event.TransferEvent;
import co.com.pragma.transfers.ports.ITransferEventPublisher;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Publicador para local y pruebas (sns.enabled=false): registra el evento en el
 * log y guarda los últimos {@value #CAPACITY} para poder verificarlos.
 */
public class InMemoryTransferEventPublisher implements ITransferEventPublisher {

    static final int CAPACITY = 500;
    private static final Logger log = LoggerFactory.getLogger(InMemoryTransferEventPublisher.class);

    private final Deque<TransferEvent> published = new ConcurrentLinkedDeque<>();

    @Override
    public Mono<Void> publish(TransferEvent event) {
        return Mono.fromRunnable(() -> {
            log.info("Evento {} transferId={} correlationId={} (SNS deshabilitado)",
                event.eventType(), event.transferId(), event.correlationId());
            published.addLast(event);
            while (published.size() > CAPACITY) {
                published.pollFirst();
            }
        });
    }

    public List<TransferEvent> published() {
        return new ArrayList<>(published);
    }

    public void clear() {
        published.clear();
    }
}
