/**
 * TP3, étape C1 : nos scénarios supplémentaires, chacun avec un BUT
 * d'observation écrit (ce qu'il doit faire apparaître, et dans quel signal).
 *
 *     node scripts/scenarios-supplementaires.mjs            (S8, S9, S10)
 *     node scripts/scenarios-supplementaires.mjs --parc-arrete   (S11 seul)
 *
 * <p>À lancer APRÈS `node resources/scenarios.mjs`, sur les mêmes services :
 * les références et les vélos attendus ci-dessous en dépendent. Pour S11,
 * arrêtez d'abord le service du parc (Ctrl-C dans son terminal), le service
 * des locations restant démarré.</p>
 *
 * <p>Le service ne renvoie aucun identifiant de trace au client, et c'est
 * voulu : ce serait exposer un détail interne. Le trace_id d'un scénario se
 * retrouve dans les journaux, par l'événement indiqué dans « où regarder ».</p>
 */
import { ABONNE, SECRET, URL_LOCATIONS, URL_PARC, appeler } from '../resources/lib.mjs';

const abonne = { courriel: ABONNE, motDePasse: SECRET };
const parcArrete = process.argv.includes('--parc-arrete');

const ouvrir = (codeStation) =>
  appeler(`${URL_LOCATIONS}/locations`, {
    methode: 'POST',
    corps: { codeStation, ...abonne },
  });
const restituer = (reference, codeStation) =>
  appeler(`${URL_LOCATIONS}/locations/${reference}/restitution`, {
    methode: 'POST',
    corps: { codeStation },
  });

const SCENARIOS = [
  {
    nom: 'S8. station inconnue',
    but: 'le 4e echec de Q2 (404) : refus venu du PARC, motif station_inconnue',
    ou: 'parc.log parc.retrait_refuse ; span parc.retirer_velo en ERROR',
    joue: () => ouvrir('ST-AILLEURS'),
  },
  {
    nom: 'S9. double restitution',
    but: 'un echec 409 entierement LOCAL : la trace du 2e rendu n a aucun span parc',
    ou: 'locations.log location.restitution_echouee etape=recherche_location',
    joue: async () => {
      const r = await ouvrir('ST-MOSSON');
      const ref = r.corps?.reference;
      await restituer(ref, 'ST-TRIOLET');
      return restituer(ref, 'ST-TRIOLET');
    },
  },
  {
    nom: 'S10. activite pour Q3',
    but: 'deux ouvertures a deux autres stations et leurs clotures : de quoi compter',
    ou: 'locations.log location.ouverte / location.cloturee ; node scripts/bilan-q3.mjs',
    joue: async () => {
      const a = await ouvrir('ST-ARCEAUX');
      const b = await ouvrir('ST-TRIOLET');
      await restituer(a.corps?.reference, 'ST-COMEDIE');
      return restituer(b.corps?.reference, 'ST-COMEDIE');
    },
  },
];

const S11 = {
  nom: 'S11. parc injoignable',
  but: 'une panne, pas un refus : 503 sans reponse du parc, cause ConnectException',
  ou: 'locations.log ERROR avec pile ; span CLIENT en erreur, 1 seul service dorsal',
  joue: () => ouvrir('ST-COMEDIE'),
};

async function parcRepond() {
  try {
    return (await appeler(`${URL_PARC}/stations`)).statut === 200;
  } catch {
    return false;
  }
}

let aJouer = SCENARIOS;
if (parcArrete) {
  if (await parcRepond()) {
    console.error('S11 exige que le service du parc soit ARRETE (Ctrl-C dans son terminal).');
    process.exit(1);
  }
  aJouer = [S11];
}

console.log('scenario                    statut   duree  but d observation');
console.log('-'.repeat(100));
for (const s of aJouer) {
  const t0 = process.hrtime.bigint();
  let statut = 0;
  try {
    statut = (await s.joue()).statut;
  } catch (e) {
    console.error(`  ${s.nom} : ${e.message}`);
  }
  const ms = Number(process.hrtime.bigint() - t0) / 1e6;
  console.log(`${s.nom.padEnd(28)}${String(statut).padEnd(8)}${ms.toFixed(0).padStart(5)} ms  ${s.but}`);
  console.log(`${''.padEnd(43)}ou regarder : ${s.ou}`);
}
