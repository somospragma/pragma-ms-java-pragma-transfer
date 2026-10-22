package co.com.pragma.transfers.application.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentacion de API viva con OpenAPI/springdoc (API-O-004 / DOC-O-002).
 * La spec se genera desde el código en /v3/api-docs (rutas anotadas con
 * @RouterOperation) y la UI queda en /swagger-ui.html.
 */
@Configuration
public class OpenApiConfig {

    static final String API_KEY = "apiKey";

    @Bean
    public OpenAPI transfersOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("ms-transfers API")
                .version("v1")
                .description("PragBank Perú - transferencias entre cuentas PragBank. Generada desde el código; "
                    + "contrato de referencia: openapi/ms-transfers-v1.yaml."))
            .components(new Components().addSecuritySchemes(API_KEY, new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("x-api-key")
                .description("API key de API Gateway (en local no se valida)")))
            .addSecurityItem(new SecurityRequirement().addList(API_KEY));
    }
}
