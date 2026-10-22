package co.com.pragma.transfers.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

class ApiKeysSecretEnvironmentPostProcessorTest {

    private static final String SECRET = "pragma-transfers-dev-secret-apis";

    private final List<String> requested = new ArrayList<>();

    private ApiKeysSecretEnvironmentPostProcessor processor(Function<String, String> reader) {
        return new ApiKeysSecretEnvironmentPostProcessor(LogFactory.getLog(getClass()), name -> {
            requested.add(name);
            return reader.apply(name);
        });
    }

    private static StandardEnvironment environmentWithSecretName(String name) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(
            new MapPropertySource("application.yml", Map.of(ApiKeysSecretEnvironmentPostProcessor.SECRET_NAME_PROPERTY, name)));
        return environment;
    }

    @Test
    void sinNombreDeSecretoNoConsultaAwsYQuedanLosValoresPorDefecto() {
        StandardEnvironment environment = environmentWithSecretName(" ");

        processor(name -> "{}").postProcessEnvironment(environment, new SpringApplication());

        assertThat(requested).isEmpty();
        assertThat(environment.getPropertySources().contains(ApiKeysSecretEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
            .isFalse();
    }

    @Test
    void exponeLasLlavesDelSecretoDespuesDeLasVariablesDeEntorno() {
        StandardEnvironment environment = environmentWithSecretName(SECRET);

        processor(name -> "{\"CORE_API_KEY\":\"core-key\",\"FRAUD_API_KEY\":\"fraud-key\",\"EMPTY\":\" \",\"N\":1}")
            .postProcessEnvironment(environment, new SpringApplication());

        assertThat(requested).containsExactly(SECRET);
        assertThat(environment.getProperty("CORE_API_KEY")).isEqualTo("core-key");
        assertThat(environment.getProperty("FRAUD_API_KEY")).isEqualTo("fraud-key");
        assertThat(environment.containsProperty("EMPTY")).isFalse();
        assertThat(environment.containsProperty("N")).isFalse();
        var sources = environment.getPropertySources();
        assertThat(sources.precedenceOf(sources.get(ApiKeysSecretEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)))
            .isEqualTo(sources.precedenceOf(sources.get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) + 1);
    }

    @Test
    void sinFuenteDeVariablesDeEntornoQuedaPrimero() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(ApiKeysSecretEnvironmentPostProcessor.SECRET_NAME_PROPERTY, SECRET);

        processor(name -> "{\"DIRECTORY_API_KEY\":\"directory-key\"}")
            .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().iterator().next().getName())
            .isEqualTo(ApiKeysSecretEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        assertThat(environment.getProperty("DIRECTORY_API_KEY")).isEqualTo("directory-key");
    }

    @Test
    void siFallaLaLecturaArrancaConLosValoresPorDefecto() {
        StandardEnvironment environment = environmentWithSecretName(SECRET);

        processor(name -> {
            throw new IllegalStateException("AccessDenied");
        }).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().contains(ApiKeysSecretEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
            .isFalse();
    }

    @Test
    void secretoVacioOSinLlavesNoAgregaNada() {
        StandardEnvironment empty = environmentWithSecretName(SECRET);
        processor(name -> null).postProcessEnvironment(empty, new SpringApplication());
        StandardEnvironment blank = environmentWithSecretName(SECRET);
        processor(name -> "{\"CORE_API_KEY\":\"\"}").postProcessEnvironment(blank, new SpringApplication());

        assertThat(empty.getPropertySources().contains(ApiKeysSecretEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
            .isFalse();
        assertThat(blank.getPropertySources().contains(ApiKeysSecretEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
            .isFalse();
    }

    @Test
    void correDespuesDeCargarApplicationYml() {
        var processor = new ApiKeysSecretEnvironmentPostProcessor(supplier -> supplier.get());

        assertThat(processor.getOrder()).isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
    }
}
