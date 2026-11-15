package hai913i.tp3.locations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import hai913i.tp3.locations.depot.LocationDepot;
import hai913i.tp3.locations.domaine.EtatLocation;
import hai913i.tp3.locations.domaine.Location;
import hai913i.tp3.locations.parc.MouvementDistant;
import hai913i.tp3.locations.parc.MouvementRefuse;
import hai913i.tp3.locations.parc.ParcDistant;
import hai913i.tp3.locations.parc.StationDistante;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * TP3, l'instrumentation manuelle du service des locations, sans agent.
 *
 * <p>Le tracer est celui d'un SDK en mémoire : on vérifie le NOM des spans,
 * leurs attributs du métier, leur statut en échec, et surtout ce qu'ils ne
 * contiennent pas (ni mot de passe, ni adresse complète). La sortie console
 * est capturée pour faire la même vérification sur le journal.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class ServiceLocationsTelemetrieTest {

    private static final String COURRIEL = "camille.rey@etu.umontpellier.fr";
    private static final String MOT_DE_PASSE = "Zx9" + "!" + "Vole-Tandem";

    private final InMemorySpanExporter exportateur = InMemorySpanExporter.create();
    private final SdkTracerProvider fournisseur = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exportateur))
            .build();

    /** Registre minimal. */
    private static final class Depot implements LocationDepot {
        private final List<Location> locations = new ArrayList<>();

        @Override
        public List<Location> toutes() {
            return List.copyOf(locations);
        }

        @Override
        public Optional<Location> parReference(String reference) {
            return locations.stream().filter(l -> l.reference().equals(reference)).findFirst();
        }

        @Override
        public int comptePourDepart(String codeDepart) {
            return 0;
        }

        @Override
        public Location ouvrir(String velo, String depart, Instant debut, String domaine) {
            Location l = new Location("L-0001", velo, depart, null, debut, null, 0, null,
                                      domaine, EtatLocation.EN_COURS);
            locations.add(l);
            return l;
        }

        @Override
        public Location cloturer(String reference, String arrivee, Instant fin, int duree,
                                 BigDecimal montant) {
            Location a = parReference(reference).orElseThrow();
            Location b = new Location(reference, a.identifiantVelo(), a.codeDepart(), arrivee,
                                      a.debut(), fin, duree, montant, a.domaineAbonne(),
                                      EtatLocation.CLOTUREE);
            locations.set(0, b);
            return b;
        }
    }

    /** Un parc qui accepte tout, sauf la gare qui n'a plus de vélo. */
    private static final class Parc implements ParcDistant {
        @Override
        public List<StationDistante> stations() {
            return List.of();
        }

        @Override
        public MouvementDistant retirer(String code) {
            if (code.equals("ST-GARE")) {
                throw new MouvementRefuse("aucun vélo disponible à la station ST-GARE");
            }
            return new MouvementDistant(code, "V-0006", 6);
        }

        @Override
        public MouvementDistant deposer(String code, String velo) {
            return new MouvementDistant(code, velo, 5);
        }

        @Override
        public int indiceRotation(String code) {
            return 100;
        }
    }

    private ServiceLocations service() {
        Clock horloge = Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), ZoneOffset.UTC);
        return new ServiceLocations(new Depot(), new Parc(), new Abonnes(), horloge,
                                    fournisseur.get("test"));
    }

    @AfterEach
    void fermer() {
        fournisseur.close();
    }

    private SpanData seul(String nom) {
        List<SpanData> spans = exportateur.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(nom)).toList();
        assertThat(spans).hasSize(1);
        return spans.get(0);
    }

    @Test
    void une_ouverture_produit_un_span_metier_sans_secret(CapturedOutput sortie) {
        service().ouvrir("ST-COMEDIE", COURRIEL, MOT_DE_PASSE);

        SpanData span = seul("location.ouvrir");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(span.getAttributes().asMap().toString())
                .contains("station.depart=ST-COMEDIE", "location.reference=L-0001",
                          "abonne.domaine=etu.umontpellier.fr")
                .doesNotContain(COURRIEL, MOT_DE_PASSE);

        assertThat(sortie.getOut()).contains("location.ouverte")
                .doesNotContain(COURRIEL, MOT_DE_PASSE.substring(0, 8), "motDePasse");
    }

    @Test
    void un_refus_du_parc_met_le_span_en_erreur_et_nomme_l_etape(CapturedOutput sortie) {
        assertThatThrownBy(() -> service().ouvrir("ST-GARE", COURRIEL, MOT_DE_PASSE))
                .isInstanceOf(MouvementRefuse.class);

        SpanData span = seul("location.ouvrir");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().asMap().toString())
                .contains("echec.etape=retrait_parc", "echec.cause=MouvementRefuse");
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
        assertThat(sortie.getOut()).contains("WARN", "location.ouverture_echouee");
    }

    @Test
    void un_abonne_refuse_echoue_a_la_premiere_etape(CapturedOutput sortie) {
        assertThatThrownBy(() -> service().ouvrir("ST-COMEDIE", COURRIEL, "faux"))
                .isInstanceOf(AbonneRefuse.class);

        SpanData span = seul("location.ouvrir");
        assertThat(span.getAttributes().asMap().toString())
                .contains("echec.etape=verification_abonne")
                .doesNotContain("faux", COURRIEL);
        assertThat(sortie.getOut()).doesNotContain(COURRIEL);
    }

    @Test
    void une_restitution_porte_le_montant_et_un_evenement() {
        ServiceLocations service = service();
        service.ouvrir("ST-COMEDIE", COURRIEL, MOT_DE_PASSE);
        service.restituer("L-0001", "ST-ARCEAUX");

        SpanData span = seul("location.restituer");
        assertThat(span.getAttributes().asMap().toString())
                .contains("location.montant_eur=1.05", "station.arrivee=ST-ARCEAUX");
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("location.facturee"));
    }
}
