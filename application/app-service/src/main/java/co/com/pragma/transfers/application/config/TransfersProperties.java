package co.com.pragma.transfers.application.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
