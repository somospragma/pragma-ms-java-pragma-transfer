package co.com.pragma.transfers.application.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuracion del servicio validada al arranque (CFG-O-004): si falta o es
 * invalida una propiedad critica, la aplicacion NO arranca (fail fast).
 */
@Validated
@ConfigurationProperties(prefix = "transfers")
public class TransfersProperties {

    /** Nombre logico del servicio. */
    @NotBlank
    private String serviceName = "ms-transfers";

    /** Tope maximo de pagina del historial (GET /accounts/{id}/transfers). */
    @Min(1)
    private int maxPageSize = 100;

    /** Reglas de negocio parametrizables (RN-04, RN-05, RN-08). */
    @Valid
    private Limits limits = new Limits();

    public Limits getLimits() {
        return limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits;
    }

    public static class Limits {

        /** RN-04: máximo por operación. */
        @NotNull
        @DecimalMin("0.01")
        private BigDecimal perTransaction = new BigDecimal("10000.00");

        /** RN-05: máximo acumulado diario por cliente. */
        @NotNull
        @DecimalMin("0.01")
        private BigDecimal daily = new BigDecimal("30000.00");

        /** Zona horaria del día de negocio (acumulado diario). */
        @NotBlank
        private String zone = "America/Lima";

        /** RN-08: vigencia de la llave de idempotencia. */
        @NotNull
        private Duration idempotencyTtl = Duration.ofHours(24);

        public BigDecimal getPerTransaction() {
            return perTransaction;
        }

        public void setPerTransaction(BigDecimal perTransaction) {
            this.perTransaction = perTransaction;
        }

        public BigDecimal getDaily() {
            return daily;
        }

        public void setDaily(BigDecimal daily) {
            this.daily = daily;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public Duration getIdempotencyTtl() {
            return idempotencyTtl;
        }

        public void setIdempotencyTtl(Duration idempotencyTtl) {
            this.idempotencyTtl = idempotencyTtl;
        }
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }
}
