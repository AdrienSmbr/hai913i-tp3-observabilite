package hai913i.tp3.locations.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import hai913i.tp3.locations.service.MesuresLocations;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

/**
 * TP3 : le seul point du service qui aille chercher OpenTelemetry.
 *
 * <p>Un {@link Tracer} et, bonus D3, les compteurs {@link MesuresLocations}
 * sont publiés, et la couche service les reçoit par injection. Quand l'agent est absent, {@code GlobalOpenTelemetry} rend une
 * implémentation no-op : le tracer existe, il n'émet rien, et aucune branche
 * conditionnelle n'est nécessaire dans le métier.</p>
 *
 * <p>Le nom d'instrumentation désigne ce code, et non une bibliothèque : dans
 * Jaeger, ce tracer distingue nos spans métier de ceux de l'agent.</p>
 */
@Configuration
public class ConfigurationTelemetrie {

    /** Nom de l'instrumentation manuelle du service des locations. */
    public static final String INSTRUMENTATION = "hai913i.tp3.locations";

    @Bean
    Tracer tracerLocations() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION, "1.0.0");
    }

    /**
     * Bonus D3 : les compteurs du métier. Même règle que pour le tracer :
     * sans agent, le meter global est no-op et les compteurs n'émettent rien.
     */
    @Bean
    MesuresLocations mesuresLocations() {
        return new MesuresLocations(GlobalOpenTelemetry.getMeter(INSTRUMENTATION));
    }
}
