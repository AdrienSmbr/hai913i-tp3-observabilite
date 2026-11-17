package hai913i.tp3.locations.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import hai913i.tp3.locations.configuration.ConfigurationTelemetrie;
import hai913i.tp3.locations.depot.LocationDepot;
import hai913i.tp3.locations.domaine.Location;
import hai913i.tp3.locations.domaine.Tarif;
import hai913i.tp3.locations.parc.MouvementDistant;
import hai913i.tp3.locations.parc.ParcDistant;
import hai913i.tp3.locations.parc.ParcIndisponible;
import hai913i.tp3.locations.parc.StationDistante;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * Les règles de la location.
 *
 * <p>C'est la couche qui porte les décisions du métier :
 * qui a le droit de louer, quel vélo sort, combien coûte un trajet.
 * Elle s'appuie sur trois collaborateurs :
 * le registre des locations,
 * le parc (derrière un port, {@link ParcDistant})
 * et une horloge.</p>
 *
 * <p>Elle ignore qu'il existe un protocole HTTP.
 * Elle rend des objets du métier ou lève une faute du métier.</p>
 *
 * <p><b>TP3, pourquoi l'observation métier est ici, et seulement ici.</b>
 * L'agent voit les bords (requête HTTP entrante, appel sortant vers le parc,
 * requête SQL). Il ne sait pas qu'une <i>location</i> a été ouverte, à quelle
 * station, ni à quelle étape elle a échoué. Cette connaissance n'existe que
 * dans la couche qui prend la décision. Le contrôleur et
 * {@code GestionErreurs} ne journalisent rien : le code de statut est déjà sur
 * le span serveur de l'agent.</p>
 *
 * <p><b>Ce qui ne sort jamais d'ici</b> : le mot de passe (il s'arrête dans
 * {@link Abonnes#verifier}), l'adresse complète (seul le domaine est émis), et
 * aucun objet n'est journalisé en entier.</p>
 */
@Service
public class ServiceLocations {

    private static final Logger log = LoggerFactory.getLogger(ServiceLocations.class);

    // Noms d'attributs et de champs : les mêmes dans le span et dans le journal,
    // pour qu'une recherche dans l'un se rejoue dans l'autre.
    static final String LOCATION_REFERENCE = "location.reference";
    static final String STATION_DEPART = "station.depart";
    static final String STATION_ARRIVEE = "station.arrivee";
    static final String VELO = "velo.identifiant";
    static final String ABONNE_DOMAINE = "abonne.domaine";
    static final String DUREE_MINUTES = "location.duree_minutes";
    static final String MONTANT_EUR = "location.montant_eur";
    static final String ECHEC_ETAPE = "echec.etape";
    static final String ECHEC_CAUSE = "echec.cause";

    private final LocationDepot depot;
    private final ParcDistant parc;
    private final Abonnes abonnes;
    private final Clock horloge;
    private final Tracer tracer;
    private final MesuresLocations mesures;

    /**
     * Constructeur d'origine, conservé pour les tests fournis qui assemblent le
     * service sans Spring. Sans agent, le tracer et le meter obtenus sont no-op.
     */
    ServiceLocations(LocationDepot depot, ParcDistant parc, Abonnes abonnes, Clock horloge) {
        this(depot, parc, abonnes, horloge,
             GlobalOpenTelemetry.getTracer(ConfigurationTelemetrie.INSTRUMENTATION));
    }

    /** Tests des spans : les métriques restent celles de l'API globale (no-op sans agent). */
    ServiceLocations(LocationDepot depot, ParcDistant parc, Abonnes abonnes, Clock horloge,
                     Tracer tracer) {
        this(depot, parc, abonnes, horloge, tracer,
             new MesuresLocations(GlobalOpenTelemetry.getMeter(ConfigurationTelemetrie.INSTRUMENTATION)));
    }

    @Autowired
    ServiceLocations(LocationDepot depot, ParcDistant parc, Abonnes abonnes, Clock horloge,
                     Tracer tracer, MesuresLocations mesures) {
        this.depot = depot;
        this.parc = parc;
        this.abonnes = abonnes;
        this.horloge = horloge;
        this.tracer = tracer;
        this.mesures = mesures;
    }

    /** Les stations, telles que le parc les décrit. */
    public List<StationDistante> stations() {
        return parc.stations();
    }

    public List<Location> locations() {
        return depot.toutes();
    }

    public Location location(String reference) {
        return depot.parReference(reference)
                .orElseThrow(() -> new LocationIntrouvable(reference));
    }

    /**
     * Ouvre une location.
     * Vérifie l'abonné, fait retirer un vélo par le parc,
     * et enregistre le départ.
     *
     * <p>Le mot de passe sert à {@link Abonnes#verifier} et s'arrête là.
     * De l'adresse, seul le domaine est conservé.</p>
     *
     * <p>TP3 : span explicite {@code location.ouvrir} (Q2, Q3), événement de
     * journal structuré {@code location.ouverte} (Q3) et, bonus D3, compteur
     * {@code velos.locations.ouvertes} (voir {@link MesuresLocations}). L'étape en cours est
     * tenue à jour pour qu'un échec dise <i>où</i> il s'est produit (Q2).</p>
     *
     * @throws AbonneRefuse l'abonné n'a pas été reconnu
     */
    public Location ouvrir(String codeStation, String courriel, String motDePasse) {
        Span span = tracer.spanBuilder("location.ouvrir")
                .setAttribute(STATION_DEPART, codeStation)
                .startSpan();
        String etape = "verification_abonne";
        //  Le catch est À L'INTÉRIEUR de la portée : la ligne d'échec porte
        //  ainsi le trace_id et le span_id de location.ouvrir.
        try (Scope ignored = span.makeCurrent()) {
            try {
                String domaine = abonnes.verifier(courriel, motDePasse);
                span.setAttribute(ABONNE_DOMAINE, domaine);

                etape = "retrait_parc";
                MouvementDistant mouvement = parc.retirer(codeStation);

                etape = "enregistrement";
                Location location = depot.ouvrir(mouvement.identifiantVelo(), codeStation,
                                                 horloge.instant(), domaine);
                span.setAttribute(LOCATION_REFERENCE, location.reference());
                span.setAttribute(VELO, location.identifiantVelo());

                //  Journal STRUCTURÉ : nom d'événement stable, champs typés.
                //  Émis une seule fois, ici, après l'enregistrement réussi.
                log.atInfo()
                   .setMessage("location.ouverte")
                   .addKeyValue(LOCATION_REFERENCE, location.reference())
                   .addKeyValue(STATION_DEPART, codeStation)
                   .addKeyValue(VELO, location.identifiantVelo())
                   .addKeyValue(ABONNE_DOMAINE, domaine)
                   .log();
                mesures.ouverte(codeStation);
                return location;
            } catch (RuntimeException faute) {
                signalerEchec(span, "location.ouverture_echouee", etape, faute,
                              STATION_DEPART, codeStation);
                mesures.echec("ouvrir", etape, faute.getClass().getSimpleName());
                throw faute;
            }
        } finally {
            span.end();
        }
    }

    /**
     * Clôture une location.
     * Fait déposer le vélo par le parc,
     * puis calcule la durée et le montant.
     *
     * <p>TP3 : span explicite {@code location.restituer}, événement de span
     * {@code location.facturee}, et événement de journal
     * {@code location.cloturee} qui porte le montant (Q3).</p>
     *
     * @throws LocationIntrouvable    la référence est inconnue
     * @throws LocationDejaCloturee   le vélo a déjà été rendu
     */
    public Location restituer(String reference, String codeStation) {
        Span span = tracer.spanBuilder("location.restituer")
                .setAttribute(LOCATION_REFERENCE, reference)
                .setAttribute(STATION_ARRIVEE, codeStation)
                .startSpan();
        String etape = "recherche_location";
        try (Scope ignored = span.makeCurrent()) {
            try {
                Location location = location(reference);
                if (!location.enCours()) {
                    throw new LocationDejaCloturee(reference);
                }
                span.setAttribute(STATION_DEPART, location.codeDepart());

                etape = "depot_parc";
                parc.deposer(codeStation, location.identifiantVelo());

                etape = "facturation";
                Instant fin = horloge.instant();
                int duree = Tarif.dureeMinutes(location.debut(), fin);
                BigDecimal montant = Tarif.montant(duree);
                Location close = depot.cloturer(reference, codeStation, fin, duree, montant);

                span.setAttribute(DUREE_MINUTES, (long) duree);
                span.setAttribute(MONTANT_EUR, montant.doubleValue());
                span.addEvent("location.facturee", Attributes.of(
                        AttributeKey.longKey(DUREE_MINUTES), (long) duree,
                        AttributeKey.doubleKey(MONTANT_EUR), montant.doubleValue()));

                log.atInfo()
                   .setMessage("location.cloturee")
                   .addKeyValue(LOCATION_REFERENCE, reference)
                   .addKeyValue(STATION_DEPART, location.codeDepart())
                   .addKeyValue(STATION_ARRIVEE, codeStation)
                   .addKeyValue(DUREE_MINUTES, duree)
                   .addKeyValue(MONTANT_EUR, montant)
                   .log();
                mesures.facturee(location.codeDepart(), montant);
                return close;
            } catch (RuntimeException faute) {
                signalerEchec(span, "location.restitution_echouee", etape, faute,
                              LOCATION_REFERENCE, reference);
                mesures.echec("restituer", etape, faute.getClass().getSimpleName());
                throw faute;
            }
        } finally {
            span.end();
        }
    }

    /**
     * Établit le rapport d'exploitation d'une location.
     *
     * <p>Pour cela, il faut une donnée que seul le parc sait calculer,
     * mais la calculer prend du temps.
     * C'est l'opération dont l'exploitation se plaint.</p>
     *
     * <p>TP3 : aucune instrumentation manuelle ici. Le span serveur, le span du
     * client HTTP vers le parc et le span JDBC du comptage sont déjà produits
     * par l'agent. Le temps se lit dans le service du parc, où il est passé.</p>
     *
     * @throws LocationIntrouvable la référence est inconnue
     */
    public Rapport rapport(String reference) {
        Location location = location(reference);
        int indice = parc.indiceRotation(location.codeDepart());
        int locationsDepuis = depot.comptePourDepart(location.codeDepart());
        return new Rapport(reference, location.codeDepart(), indice, locationsDepuis);
    }

    /**
     * Marque le span en erreur et écrit UNE ligne d'échec.
     *
     * <p>Niveau : WARN quand le service a correctement appliqué une règle du
     * métier et a répondu un refus (abonné inconnu, station inconnue, plus de
     * vélo, location déjà close). ERROR quand il n'a pas pu faire son travail
     * parce que le service du parc est indisponible.</p>
     *
     * <p>L'exception n'est passée en dernier argument (ici {@code setCause},
     * l'équivalent de l'API fluide) que si elle porte une cause technique, par
     * exemple une connexion refusée quand le parc est arrêté : sa pile est
     * alors utile. Quand le parc a répondu « station hors service », la pile
     * de {@code ParcIndisponible} ne dirait rien de plus que {@code echec.cause}
     * et ne serait que du bruit.</p>
     *
     * <p>{@code echec.cause} est le nom simple de la classe de la faute :
     * une poignée de valeurs possibles, jamais un message libre.</p>
     */
    private static void signalerEchec(Span span, String evenement, String etape,
                                      RuntimeException faute, String cle, String valeur) {
        String cause = faute.getClass().getSimpleName();
        span.setStatus(StatusCode.ERROR, cause);
        span.setAttribute(ECHEC_ETAPE, etape);
        span.setAttribute(ECHEC_CAUSE, cause);
        span.recordException(faute);

        boolean indisponible = faute instanceof ParcIndisponible;
        var ligne = (indisponible ? log.atError() : log.atWarn())
                .setMessage(evenement)
                .addKeyValue(ECHEC_ETAPE, etape)
                .addKeyValue(ECHEC_CAUSE, cause)
                .addKeyValue(cle, valeur);
        if (faute.getCause() != null) {
            ligne = ligne.setCause(faute);
        }
        ligne.log();
    }
}
