/**
 * TP3, Q3 : « Combien de locations ont été ouvertes, depuis quelles
 * stations, et pour quels montants ? »
 *
 *     node scripts/bilan-q3.mjs                       (journaux/locations.log)
 *     node scripts/bilan-q3.mjs --journal <fichier>
 *
 * <p>La réponse est calculée UNIQUEMENT à partir du journal structuré : les
 * événements `location.ouverte` et `location.cloturee`, et leurs champs typés.
 * C'est la démonstration qu'un journal structuré s'interroge, là où une
 * phrase libre devrait être analysée à coups d'expressions régulières.</p>
 *
 * <p>Ce que ce bilan NE dit PAS : il ne compte que ce qui a été journalisé
 * depuis le démarrage du service et dans ce fichier. Il ne voit pas les
 * locations d'une autre instance, ni celles d'avant une rotation du fichier.</p>
 */
import fs from 'node:fs/promises';

const args = process.argv.slice(2);
const i = args.indexOf('--journal');
const fichier = i !== -1 && args[i + 1] ? args[i + 1] : 'journaux/locations.log';

let texte;
try {
  texte = await fs.readFile(fichier, 'utf8');
} catch {
  console.error(`journal introuvable : ${fichier}`);
  process.exit(1);
}

const evenements = texte
  .split('\n')
  .filter((l) => l.startsWith('{'))
  .map((l) => {
    try {
      return JSON.parse(l);
    } catch {
      return null;
    }
  })
  .filter(Boolean);

const ouvertes = evenements.filter((e) => e.message === 'location.ouverte');
const cloturees = evenements.filter((e) => e.message === 'location.cloturee');
const refus = evenements.filter((e) => e.message === 'location.ouverture_echouee');

const parStation = new Map();
const ligne = (code) => {
  if (!parStation.has(code)) {
    parStation.set(code, { ouvertes: 0, cloturees: 0, montant: 0, refus: 0 });
  }
  return parStation.get(code);
};
for (const e of ouvertes) ligne(e.station.depart).ouvertes += 1;
for (const e of cloturees) {
  const l = ligne(e.station.depart);
  l.cloturees += 1;
  l.montant += Number(e.location.montant_eur);
}
for (const e of refus) ligne(e.station?.depart ?? '?').refus += 1;

console.log(`source : ${fichier} (${evenements.length} enregistrements)`);
console.log('');
console.log('station de depart   ouvertes  cloturees  montant facture   ouvertures refusees');
for (const [code, l] of [...parStation.entries()].sort()) {
  console.log(
    `${code.padEnd(20)}${String(l.ouvertes).padStart(8)}${String(l.cloturees).padStart(11)}` +
      `${l.montant.toFixed(2).padStart(15)} EUR${String(l.refus).padStart(14)}`,
  );
}
const total = cloturees.reduce((s, e) => s + Number(e.location.montant_eur), 0);
console.log('');
console.log(
  `total : ${ouvertes.length} location(s) ouverte(s), ${cloturees.length} cloturee(s), ` +
    `${total.toFixed(2)} EUR factures, ${refus.length} ouverture(s) refusee(s)`,
);
const domaines = new Set(ouvertes.map((e) => e.abonne?.domaine));
console.log(`domaines d abonnes vus : ${[...domaines].join(', ') || '(aucun)'}`);
