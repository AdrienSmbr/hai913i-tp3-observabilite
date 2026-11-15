package hai913i.tp3.locations.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import hai913i.tp3.locations.depot.LocationDepot;
import hai913i.tp3.locations.domaine.Location;
import hai913i.tp3.locations.domaine.Tarif;
import hai913i.tp3.locations.parc.MouvementDistant;
import hai913i.tp3.locations.parc.ParcDistant;
import hai913i.tp3.locations.parc.ParcIndisponible;
import hai913i.tp3.locations.parc.StationDistante;

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
 * <p><b>TP3, pourquoi les journaux métier sont ici, et seulement ici.</b>
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

    // Noms des champs du journal, définis une fois.
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

    ServiceLocations(LocationDepot depot, ParcDistant parc, Abonnes abonnes, Clock horloge) {
        this.depot = depot;
        this.parc = parc;
        this.abonnes = abonnes;
        this.horloge = horloge;
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
     * <p>TP3 : événement de journal structuré {@code location.ouverte} (Q3).
     * L'étape en cours est tenue à jour pour qu'un échec dise <i>où</i> il
     * s'est produit (Q2).</p>
     *
     * @throws AbonneRefuse l'abonné n'a pas été reconnu
     */
    public Location ouvrir(String codeStation, String courriel, String motDePasse) {
        String etape = "verification_abonne";
        try {
            String domaine = abonnes.verifier(courriel, motDePasse);

            etape = "retrait_parc";
            MouvementDistant mouvement = parc.retirer(codeStation);

            etape = "enregistrement";
            Location location = depot.ouvrir(mouvement.identifiantVelo(), codeStation,
                                             horloge.instant(), domaine);

            //  Journal STRUCTURÉ : nom d'événement stable, champs typés.
            //  Émis une seule fois, ici, après l'enregistrement réussi.
            log.atInfo()
               .setMessage("location.ouverte")
               .addKeyValue(LOCATION_REFERENCE, location.reference())
               .addKeyValue(STATION_DEPART, codeStation)
               .addKeyValue(VELO, location.identifiantVelo())
               .addKeyValue(ABONNE_DOMAINE, domaine)
               .log();
            return location;
        } catch (RuntimeException faute) {
            journaliserEchec("location.ouverture_echouee", etape, faute,
                             STATION_DEPART, codeStation);
            throw faute;
        }
    }

    /**
     * Clôture une location.
     * Fait déposer le vélo par le parc,
     * puis calcule la durée et le montant.
     *
     * <p>TP3 : événement de journal {@code location.cloturee}, qui porte le
     * montant (Q3).</p>
     *
     * @throws LocationIntrouvable    la référence est inconnue
     * @throws LocationDejaCloturee   le vélo a déjà été rendu
     */
    public Location restituer(String reference, String codeStation) {
        String etape = "recherche_location";
        try {
            Location location = location(reference);
            if (!location.enCours()) {
                throw new LocationDejaCloturee(reference);
            }

            etape = "depot_parc";
            parc.deposer(codeStation, location.identifiantVelo());

            etape = "facturation";
            Instant fin = horloge.instant();
            int duree = Tarif.dureeMinutes(location.debut(), fin);
            BigDecimal montant = Tarif.montant(duree);
            Location close = depot.cloturer(reference, codeStation, fin, duree, montant);

            log.atInfo()
               .setMessage("location.cloturee")
               .addKeyValue(LOCATION_REFERENCE, reference)
               .addKeyValue(STATION_DEPART, location.codeDepart())
               .addKeyValue(STATION_ARRIVEE, codeStation)
               .addKeyValue(DUREE_MINUTES, duree)
               .addKeyValue(MONTANT_EUR, montant)
               .log();
            return close;
        } catch (RuntimeException faute) {
            journaliserEchec("location.restitution_echouee", etape, faute,
                             LOCATION_REFERENCE, reference);
            throw faute;
        }
    }

    /**
     * Établit le rapport d'exploitation d'une location.
     *
     * <p>Pour cela, il faut une donnée que seul le parc sait calculer,
     * mais la calculer prend du temps.
     * C'est l'opération dont l'exploitation se plaint.</p>
     *
     * <p>TP3 : aucun journal ici. La question « où passe le temps ? » relève
     * des traces (partie B), pas d'une ligne qui redirait une durée.</p>
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
     * Écrit UNE ligne d'échec.
     *
     * <p>Niveau : WARN quand le service a correctement appliqué une règle du
     * métier et a répondu un refus (abonné inconnu, station inconnue, plus de
     * vélo, location déjà close). ERROR quand il n'a pas pu faire son travail
     * parce que le service du parc est indisponible.</p>
     *
     * <p>L'exception n'est passée en dernier argument ({@code setCause},
     * l'équivalent de l'API fluide) que si elle porte une cause technique, par
     * exemple une connexion refusée quand le parc est arrêté. Sa pile est
     * alors utile. Quand le parc a répondu « station hors service », la pile
     * ne dirait rien de plus que {@code echec.cause}.</p>
     *
     * <p>{@code echec.cause} est le nom simple de la classe de la faute :
     * une poignée de valeurs possibles, jamais un message libre.</p>
     */
    private static void journaliserEchec(String evenement, String etape,
                                         RuntimeException faute, String cle, String valeur) {
        boolean indisponible = faute instanceof ParcIndisponible;
        var ligne = (indisponible ? log.atError() : log.atWarn())
                .setMessage(evenement)
                .addKeyValue(ECHEC_ETAPE, etape)
                .addKeyValue(ECHEC_CAUSE, faute.getClass().getSimpleName())
                .addKeyValue(cle, valeur);
        if (faute.getCause() != null) {
            ligne = ligne.setCause(faute);
        }
        ligne.log();
    }
}
