package co.com.pragma.transfers.application.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lee al arrancar el secreto {@code secrets.apis-name} (SECRET_APIS_NAME) con el task role y
 * expone cada llave del JSON (CORE_API_KEY, FRAUD_API_KEY, DIRECTORY_API_KEY) como propiedad.
 *
 * <p>Las variables de entorno siguen teniendo prioridad. Si no hay nombre, el secreto no existe o
 * no hay permisos, el micro arranca con los valores por defecto de application.yml.
 */
public class ApiKeysSecretEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SECRET_NAME_PROPERTY = "secrets.apis-name";
    static final String PROPERTY_SOURCE_NAME = "secretsManager:apis";

    private final Log log;
    private final Function<String, String> secretReader;

    public ApiKeysSecretEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this(logFactory.getLog(ApiKeysSecretEnvironmentPostProcessor.class),
            ApiKeysSecretEnvironmentPostProcessor::readFromSecretsManager);
    }

    ApiKeysSecretEnvironmentPostProcessor(Log log, Function<String, String> secretReader) {
        this.log = log;
        this.secretReader = secretReader;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String secretName = environment.getProperty(SECRET_NAME_PROPERTY, "").trim();
        if (secretName.isEmpty()) {
            log.info("Sin SECRET_APIS_NAME: las llaves de las APIs usan los valores por defecto");
            return;
        }
        try {
            Map<String, Object> keys = parse(secretReader.apply(secretName));
            if (keys.isEmpty()) {
                log.warn("El secreto " + secretName + " no tiene llaves: se usan los valores por defecto");
                return;
            }
            addAfterSystemEnvironment(environment.getPropertySources(), new MapPropertySource(PROPERTY_SOURCE_NAME, keys));
            log.info("Llaves de las APIs leídas del secreto " + secretName + ": " + keys.keySet());
        } catch (RuntimeException e) {
            log.warn("No se pudo leer el secreto " + secretName + " (" + e.getMessage()
                + "): se usan los valores por defecto");
        }
    }

    /** Después de application.yml, para leer secrets.apis-name ya resuelto. */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    private static Map<String, Object> parse(String json) {
        Map<String, Object> keys = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return keys;
        }
        Map<?, ?> values = JsonMapper.builder().build().readValue(json, Map.class);
        values.forEach((key, value) -> {
            if (value instanceof String text && !text.isBlank()) {
                keys.put(key.toString(), text);
            }
        });
        return keys;
    }

    private static void addAfterSystemEnvironment(MutablePropertySources sources, MapPropertySource secret) {
        String systemEnvironment = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        if (sources.contains(systemEnvironment)) {
            sources.addAfter(systemEnvironment, secret);
        } else {
            sources.addFirst(secret);
        }
    }

    private static String readFromSecretsManager(String secretName) {
        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .build()) {
            return client.getSecretValue(request -> request.secretId(secretName)).secretString();
        }
    }
}
