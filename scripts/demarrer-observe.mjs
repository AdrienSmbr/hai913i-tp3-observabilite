/**
 * TP3, démarre un service AU PREMIER PLAN, avec l'agent Java OpenTelemetry.
 *
 *     node scripts/demarrer-observe.mjs parc
 *     node scripts/demarrer-observe.mjs locations
 *
 * <p>Le JAR est exactement celui que lance `demarrer.mjs` : l'agent se greffe
 * sans toucher au code. Ce script ne fait qu'ajouter les arguments JVM
 * ci-dessous, puis délègue à `demarrer.mjs` (qui vérifie le port et le JAR).
 * Tout argument supplémentaire est transmis tel quel à la JVM.</p>
 *
 * <p>Arguments ajoutés, et pourquoi :</p>
 * <ul>
 *   <li>`-javaagent:opentelemetry-javaagent.jar` : l'agent 2.32.0, téléchargé
 *       à la racine par `node resources/telecharger-agent.mjs` ;</li>
 *   <li>`otel.service.name` : un nom distinct par service (`parc`,
 *       `locations`), c'est ce nom que Jaeger affiche ;</li>
 *   <li>`otel.exporter.otlp.endpoint` : le Collector, en gRPC, et jamais
 *       Jaeger directement ;</li>
 *   <li>`otel.metrics.exporter=none` et `otel.logs.exporter=none` : seules
 *       les TRACES partent vers le Collector. Les journaux restent dans des
 *       fichiers (Jaeger n'est pas un visualiseur de journaux), et aucune des
 *       trois questions ne demande de métrique.</li>
 * </ul>
 */
import { spawn } from 'node:child_process';
import path from 'node:path';

import { RACINE, SERVICES, existe } from './lib.mjs';

const [nom, ...jvmEnPlus] = process.argv.slice(2);

if (!nom || !SERVICES[nom]) {
  console.error('usage : node scripts/demarrer-observe.mjs <parc|locations> [arguments JVM...]');
  process.exit(2);
}

const agent = path.join(RACINE, 'opentelemetry-javaagent.jar');
if (!(await existe(agent))) {
  console.error('agent absent — telechargez-le depuis la racine :');
  console.error('    node resources/telecharger-agent.mjs');
  process.exit(1);
}

const collecteur = process.env.OTEL_EXPORTER_OTLP_ENDPOINT ?? 'http://localhost:4317';

const argumentsAgent = [
  `-javaagent:${agent}`,
  `-Dotel.service.name=${nom}`,
  `-Dotel.exporter.otlp.endpoint=${collecteur}`,
  '-Dotel.exporter.otlp.protocol=grpc',
  '-Dotel.metrics.exporter=none',
  '-Dotel.logs.exporter=none',
];

console.log(`agent OpenTelemetry : service « ${nom} » -> ${collecteur}`);

const p = spawn(
  process.execPath,
  [path.join(RACINE, 'scripts', 'demarrer.mjs'), nom, ...argumentsAgent, ...jvmEnPlus],
  { stdio: 'inherit' },
);
p.on('exit', (code, signal) => process.exit(signal ? 0 : code ?? 0));
for (const s of ['SIGINT', 'SIGTERM']) {
  process.on(s, () => p.kill(s));
}
