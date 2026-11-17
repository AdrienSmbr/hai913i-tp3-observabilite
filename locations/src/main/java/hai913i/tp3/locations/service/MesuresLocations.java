package hai913i.tp3.locations.service;

import java.math.BigDecimal;
import java.util.Set;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

/**
 * Bonus D3 : les métriques du métier des locations.
 *
 * <p><b>La question.</b> Combien de locations chaque station a-t-elle ouvertes,
 * combien ont-elles rapporté, et combien d'opérations échouent, à quelle étape,
 * depuis le démarrage et au fil du temps ? C'est Q3, plus la tendance de Q2.
 * Le journal y répond en recomptant ses lignes, tant qu'elles existent
 * encore (rotation, rétention, plusieurs instances). Les traces y répondent
 * mal : une trace décrit une requête, et en exploitation elles seraient
 * échantillonnées. Un compteur est une agrégation : il coûte une addition par
 * événement et ne dépend ni de la rétention ni de l'échantillonnage.</p>
 *
 * <p><b>La cardinalité, dimension par dimension.</b></p>
 * <ul>
 *   <li>{@code station.depart} : seulement après un retrait ou une
 *       restitution réussis, donc une station que le parc connaît. 6
 *       aujourd'hui, la taille du réseau demain. Un code inconnu (S8) n'est
 *       JAMAIS une valeur d'attribut : il échoue avant et n'est compté que
 *       dans les échecs, sans sa station.</li>
 *   <li>{@code operation} : 2 valeurs ({@code ouvrir}, {@code restituer}).</li>
 *   <li>{@code echec.etape} : 6 valeurs, 3 par opération, fixées par le
 *       code.</li>
 *   <li>{@code echec.cause} : 7 valeurs au plus. Les 6 fautes du métier
 *       ({@link #CAUSES_CONNUES}), et toute autre exception repliée sur
 *       {@code Autre}, pour que la dimension reste fermée même si le code
 *       change.</li>
 * </ul>
 * <p>6 séries au plus pour chacun des deux premiers compteurs. Pour le
 * troisième, 9 combinaisons atteignables (4 pour ouvrir, 5 pour restituer),
 * plus une par étape pour {@code Autre}. Avec {@code target_info}, une
 * vingtaine de séries par instance. Ni référence de location, ni vélo, ni abonné, ni domaine :
 * ce sont des identifiants (cardinalité non bornée) ou des données
 * personnelles, et ils ont déjà leur place dans les spans et le journal.</p>
 */
public class MesuresLocations {

    static final AttributeKey<String> STATION = AttributeKey.stringKey(ServiceLocations.STATION_DEPART);
    static final AttributeKey<String> OPERATION = AttributeKey.stringKey("operation");
    static final AttributeKey<String> ETAPE = AttributeKey.stringKey(ServiceLocations.ECHEC_ETAPE);
    static final AttributeKey<String> CAUSE = AttributeKey.stringKey(ServiceLocations.ECHEC_CAUSE);

    /** Les fautes du métier. Toute autre cause est comptée comme {@code Autre}. */
    static final Set<String> CAUSES_CONNUES = Set.of(
            "AbonneRefuse", "MouvementRefuse", "ParcIndisponible", "StationInconnue",
            "LocationIntrouvable", "LocationDejaCloturee");

    private final LongCounter ouvertes;
    private final DoubleCounter montant;
    private final LongCounter echecs;

    public MesuresLocations(Meter meter) {
        this.ouvertes = meter.counterBuilder("velos.locations.ouvertes")
                .setDescription("Locations ouvertes, par station de départ")
                .setUnit("{location}")
                .build();
        this.montant = meter.counterBuilder("velos.locations.montant")
                .ofDoubles()
                .setDescription("Montant facturé à la restitution, par station de départ")
                .setUnit("EUR")
                .build();
        this.echecs = meter.counterBuilder("velos.locations.echecs")
                .setDescription("Ouvertures et restitutions échouées, par étape et par cause")
                .setUnit("{operation}")
                .build();
    }

    void ouverte(String stationDepart) {
        ouvertes.add(1, Attributes.of(STATION, stationDepart));
    }

    void facturee(String stationDepart, BigDecimal euros) {
        montant.add(euros.doubleValue(), Attributes.of(STATION, stationDepart));
    }

    void echec(String operation, String etape, String cause) {
        String bornee = CAUSES_CONNUES.contains(cause) ? cause : "Autre";
        echecs.add(1, Attributes.of(OPERATION, operation, ETAPE, etape, CAUSE, bornee));
    }
}
