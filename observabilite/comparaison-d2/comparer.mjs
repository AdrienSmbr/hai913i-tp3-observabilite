/**
 * Bonus D2 : compare une même trace avant et après resource/minimiser.
 *
 *     node observabilite/comparaison-d2/comparer.mjs [trace_id]
 *
 * Lit sortie/avant.json et sortie/apres.json (une ligne OTLP/JSON par lot,
 * écrites par la configuration de preuve). Sans trace_id, prend la trace qui
 * a le plus de spans. Imprime, pour chaque service de la trace, les attributs
 * de ressource avant et après, et la taille des lots qui la contiennent.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ici = path.dirname(fileURLToPath(import.meta.url));
const lire = (nom) => fs.readFileSync(path.join(ici, 'sortie', nom), 'utf8')
  .split('\n').filter(Boolean).map((l) => ({ octets: Buffer.byteLength(l), lot: JSON.parse(l) }));
const avant = lire('avant.json');
const apres = lire('apres.json');

function spansParTrace(lots) {
  const n = new Map();
  for (const { lot } of lots)
    for (const rs of lot.resourceSpans ?? [])
      for (const ss of rs.scopeSpans ?? [])
        for (const s of ss.spans ?? []) n.set(s.traceId, (n.get(s.traceId) ?? 0) + 1);
  return n;
}
let trace = process.argv[2];
if (!trace) trace = [...spansParTrace(avant)].sort((a, b) => b[1] - a[1])[0][0];

const valeur = (v) => v.stringValue ?? v.intValue ?? v.boolValue ?? v.doubleValue
  ?? (v.arrayValue ? `[${(v.arrayValue.values ?? []).map(valeur).join(', ')}]` : JSON.stringify(v));
function ressources(lots) {
  const parService = new Map();
  let octets = 0, spans = 0;
  for (const { octets: o, lot } of lots) {
    let dedans = false;
    for (const rs of lot.resourceSpans ?? []) {
      const ici = (rs.scopeSpans ?? []).flatMap((ss) => ss.spans ?? []).filter((s) => s.traceId === trace);
      if (!ici.length) continue;
      dedans = true; spans += ici.length;
      const attrs = Object.fromEntries((rs.resource?.attributes ?? []).map((a) => [a.key, valeur(a.value)]));
      parService.set(attrs['service.name'], { attrs, octets: Buffer.byteLength(JSON.stringify(rs.resource ?? {})) });
    }
    if (dedans) octets += o;
  }
  return { parService, octets, spans };
}
const A = ressources(avant), P = ressources(apres);
console.log(`trace ${trace} : ${A.spans} span(s) avant, ${P.spans} après`);
for (const [service, a] of A.parService) {
  const p = P.parService.get(service);
  const retires = Object.keys(a.attrs).filter((k) => !(k in p.attrs));
  console.log(`\nservice « ${service} » : ${Object.keys(a.attrs).length} attributs de ressource avant, ${Object.keys(p.attrs).length} après`);
  console.log(`  ressource sérialisée : ${a.octets} octets avant, ${p.octets} après (-${a.octets - p.octets})`);
  for (const k of Object.keys(p.attrs).filter((k) => k in a.attrs && a.attrs[k] !== p.attrs[k]))
    // La valeur d'origine n'est pas recopiée dans la preuve (elle nomme le poste) :
    // on montre sa forme et sa longueur, et l'empreinte qui la remplace.
    console.log(`  modifié ${k} : <nom du poste, ${String(a.attrs[k]).length} caractères>  ->  ${p.attrs[k]}`);
  for (const k of retires) {
    const v = String(a.attrs[k]);
    console.log(`  retiré  ${k} = ${v.slice(0, 160)}${v.length > 160 ? '…' : ''}`);
    // Le nom du compte n'est pas recopié dans la preuve : on dit seulement qu'il y était.
    const perso = v.match(/\/(Users|home)\/[^/\s,\]]+/);
    if (perso) console.log(`          (contient un dossier personnel : /${perso[1]}/<compte>/..., ${v.length} caractères)`);
  }
  console.log(`  gardé   ${Object.keys(p.attrs).sort().join(', ')}`);
}
console.log(`\nlots contenant la trace : ${A.octets} octets avant, ${P.octets} après (-${A.octets - P.octets}, ${((A.octets - P.octets) / A.octets * 100).toFixed(1)} %)`);
const total = (l) => l.reduce((s, x) => s + x.octets, 0);
console.log(`ensemble des lots      : ${total(avant)} octets avant, ${total(apres)} après (-${((total(avant) - total(apres)) / total(avant) * 100).toFixed(1)} %)`);
