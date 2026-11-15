package hai913i.tp3.parc.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

/**
 * TP3 : le seul point du service du parc qui aille chercher OpenTelemetry.
 *
 * <p>Sans agent, le tracer publié est no-op et le service se comporte à
 * l'identique (c'est le cas des tests).</p>
 */
@Configuration
public class ConfigurationTelemetrie {

    /** Nom de l'instrumentation manuelle du service du parc. */
    public static final String INSTRUMENTATION = "hai913i.tp3.parc";

    @Bean
    Tracer tracerParc() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION, "1.0.0");
    }
}
