package co.com.pragma.transfers.ports;

import co.com.pragma.transfers.model.transfer.Transfer;
import java.math.BigDecimal;
import java.time.LocalDate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Persistencia de transferencias (tabla transfers). */
public interface ITransferGateway {

    /** Crea o reemplaza la transferencia (por transferId). */
    Mono<Transfer> save(Transfer transfer);

    Mono<Transfer> findById(String transferId);

    /** Transferencias donde la cuenta es origen, de la más reciente a la más antigua. */
    Flux<Transfer> findBySourceAccount(String sourceAccountId);

    /**
     * RN-05: suma de los montos del cliente en el día de negocio, en la moneda
     * indicada, de las transferencias que cuentan para el límite diario.
     */
    Mono<BigDecimal> sumDailyAmount(String customerId, LocalDate businessDate, String currency);
}
