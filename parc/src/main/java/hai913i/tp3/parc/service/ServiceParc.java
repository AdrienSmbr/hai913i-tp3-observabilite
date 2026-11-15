package hai913i.tp3.parc.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import hai913i.tp3.parc.depot.ParcDepot;
import hai913i.tp3.parc.domaine.EtatVelo;
import hai913i.tp3.parc.domaine.Station;
import hai913i.tp3.parc.domaine.Velo;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;

/**
 * Les règles du parc.
 *
 * <p>Cette classe ignore qu'il existe un protocole HTTP.
 * Elle rend des objets du métier ou lève une faute du métier.
 * C'est la couche API qui décide du code de statut.</p>
 *
 * <p><b>TP3.</b> Trois points d'instrumentation, tous dans cette couche :
 * le calcul de l'indice (span explicite, Q1), son étape de consolidation
 * (annotation, Q1) et le retrait d'un vélo (annotation, Q2). Le contrôleur
 * et le dépôt n'en portent aucun : l'agent produit déjà le span HTTP, les
 * spans Spring Data et les spans JDBC.</p>
 */
@Service
public class ServiceParc {

    private static final Logger log = LoggerFactory.getLogger(ServiceParc.class);

    private final ParcDepot depot;
    private final int consolidationMs;
    private final Tracer tracer;

    ServiceParc(ParcDepot depot, @Value("${parc.consolidation-ms:500}") int consolidationMs,
                Tracer tracer) {
        this.depot = depot;
        this.consolidationMs = consolidationMs;
        this.tracer = tracer;
    }

    public List<Station> stations() {
        return depot.stations();
    }

    public Station station(String code) {
        return depot.parCode(code).orElseThrow(() -> new StationIntrouvable(code));
    }

    public int disponibles(String code) {
        return depot.disponibles(code);
    }

    /**
     * Retire un vélo d'une station.
     *
     * <p>TP3 : l'annotation suffit ici, parce qu'il s'agit seulement de faire
     * apparaître l'étape. C'est l'agent qui crée le span. Une faute qui sort
     * de la méthode le passe en erreur et y est enregistrée. La ligne WARN dit
     * POURQUOI le parc a refusé, ce que le code 409 ou 503 seul ne dit pas.</p>
     *
     * @return le vélo retiré
     * @throws StationIntrouvable  le code ne correspond à aucune station
     * @throws StationIndisponible la station est hors service
     * @throws MouvementImpossible la station n'a plus aucun vélo disponible
     */
    @WithSpan("parc.retirer_velo")
    public Velo retirer(@SpanAttribute("station.code") String code) {
        try {
            Station station = station(code);
            if (station.horsService()) {
                throw new StationIndisponible(code);
            }
            Velo velo = depot.premierDisponible(code)
                    .orElseThrow(() -> new MouvementImpossible(
                            "aucun vélo disponible à la station " + code));
            depot.retirer(velo.identifiant());
            return new Velo(velo.identifiant(), null, EtatVelo.EN_COURSE);
        } catch (StationIntrouvable | StationIndisponible | MouvementImpossible refus) {
            //  Un refus du métier : le parc fonctionne et répond. WARN, sans
            //  pile, avec un motif à trois valeurs possibles.
            log.atWarn()
               .setMessage("parc.retrait_refuse")
               .addKeyValue("station.code", code)
               .addKeyValue("refus.motif", motif(refus))
               .log();
            throw refus;
        }
    }

    private static String motif(RuntimeException refus) {
        if (refus instanceof StationIntrouvable) {
            return "station_inconnue";
        }
        if (refus instanceof StationIndisponible) {
            return "station_hors_service";
        }
        return "aucun_velo_disponible";
    }

    /**
     * Dépose un vélo à une station.
     *
     * @return le vélo déposé
     * @throws StationIntrouvable  le code ne correspond à aucune station
     * @throws StationIndisponible la station est hors service
     * @throws MouvementImpossible le vélo est inconnu, il n'est pas en
     *                             course, ou toutes les bornes sont occupées
     */
    public Velo deposer(String code, String identifiant) {
        Station station = station(code);
        if (station.horsService()) {
            throw new StationIndisponible(code);
        }
        Velo velo = depot.velo(identifiant)
                .orElseThrow(() -> new MouvementImpossible("vélo inconnu : " + identifiant));
        if (velo.etat() != EtatVelo.EN_COURSE) {
            throw new MouvementImpossible("le vélo " + identifiant + " n'est pas en course");
        }
        if (depot.presents(code) >= station.capacite()) {
            throw new MouvementImpossible("toutes les bornes de " + code + " sont occupées");
        }
        depot.deposer(identifiant, code);
        return new Velo(identifiant, code, EtatVelo.DISPONIBLE);
    }

    /**
     * Consolide l'indice de rotation d'une station.
     *
     * <p>L'indice vaut, en pour cent, la part des vélos rattachés à la
     * station qui sont réellement retirables.
     * La consolidation prend un temps <b>fixe et volontairement long</b>.
     * C'est l'opération que l'exploitation trouve lente.</p>
     *
     * <p>TP3 : span EXPLICITE {@code parc.indice_rotation}, parce qu'il porte
     * des attributs du métier (station, vélos rattachés, indice obtenu). Ses
     * enfants sont les deux lectures Spring Data / JDBC de l'agent et l'étape
     * {@code parc.consolidation} : c'est ce découpage qui répond à Q1.</p>
     *
     * @throws StationIntrouvable le code ne correspond à aucune station
     */
    public int indiceRotation(String code) {
        Span span = tracer.spanBuilder("parc.indice_rotation")
                .setAttribute("station.code", code)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Station station = station(code);
            List<Velo> velos = depot.velosDe(station.code());
            span.setAttribute("parc.velos_rattaches", (long) velos.size());
            consolider(consolidationMs);
            if (velos.isEmpty()) {
                span.setAttribute("parc.indice_rotation", 0L);
                return 0;
            }
            long retirables = velos.stream().filter(v -> v.etat() == EtatVelo.DISPONIBLE).count();
            int indice = (int) Math.round(100.0 * retirables / velos.size());
            span.setAttribute("parc.indice_rotation", (long) indice);
            return indice;
        } catch (RuntimeException faute) {
            span.setStatus(StatusCode.ERROR, faute.getClass().getSimpleName());
            span.recordException(faute);
            throw faute;
        } finally {
            span.end();
        }
    }

    /**
     * L'étape de consolidation de l'indice.
     *
     * <p>TP3 : annotation seule, parce qu'il suffit de faire apparaître l'étape
     * (et sa durée) dans la trace. Sans agent, l'annotation ne fait rien.</p>
     */
    @WithSpan("parc.consolidation")
    void consolider(@SpanAttribute("parc.consolidation_ms") int millisecondes) {
        attendre(millisecondes);
    }

    private static void attendre(int millisecondes) {
        try {
            Thread.sleep(millisecondes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
