/**
 * TP3, démarrage de la télémétrie du navigateur.
 *
 * C'EST LE SEUL FICHIER VOLATIL. Il est le seul à importer le SDK
 * OpenTelemetry, l'exportateur et l'instrumentation de `fetch`.
 *
 *   · la documentation d'OpenTelemetry indique que l'instrumentation cliente
 *     du navigateur est expérimentale et en grande partie non spécifiée.
 *   · les noms de paquets et les interfaces peuvent changer d'une version à
 *     l'autre : le jour où ils changent, c'est ce fichier, et lui seul, qu'il
 *     faut reprendre. Le reste de l'application n'importe que
 *     `@opentelemetry/api` (stable 1.x), via `observability.ts`.
 *
 * Ce qu'on émet, et ce qu'on n'émet pas :
 *   · TRACES : oui, le span du geste (manuel) et le span du `fetch`
 *                 (automatique), qui porte l'en-tête `traceparent` jusqu'au
 *                 service des locations.
 *   · JOURNAUX : non par OpenTelemetry (signal encore en développement en
 *                 JavaScript) : la console, avec le trace_id ajouté à la main
 *                 (voir journal-navigateur.ts).
 *   · MÉTRIQUES : non, aucune des trois questions ne les demande au
 *                 navigateur.
 */
import { inject, Injectable } from '@angular/core';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { BatchSpanProcessor } from '@opentelemetry/sdk-trace-base';
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import {
  ATTR_SERVICE_NAME,
  ATTR_SERVICE_VERSION,
} from '@opentelemetry/semantic-conventions';
import { FetchInstrumentation } from '@opentelemetry/browser-instrumentation/experimental/fetch';

import { CONFIGURATION } from '../configuration';

/** Échappe une URL pour l'employer comme préfixe d'expression régulière. */
const prefixe = (url: string) =>
  new RegExp('^' + url.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));

@Injectable({ providedIn: 'root' })
export class TelemetryBootstrap {
  private readonly configuration = inject(CONFIGURATION);
  private demarre = false;

  /**
   * Installe le fournisseur de traces et l'instrumentation de `fetch`.
   *
   * <p>Appelé par `provideAppInitializer`, donc AVANT le premier rendu et
   * avant la première requête : une instrumentation posée après le premier
   * `fetch` ne le voit pas.</p>
   */
  demarrer(): void {
    if (this.demarre) {
      return;
    }
    this.demarre = true;

    const fournisseur = new WebTracerProvider({
      resource: resourceFromAttributes({
        [ATTR_SERVICE_NAME]: this.configuration.nomService,
        [ATTR_SERVICE_VERSION]: this.configuration.versionService,
      }),
      spanProcessors: [
        new BatchSpanProcessor(
          new OTLPTraceExporter({ url: this.configuration.urlCollecteurOtlp }),
          { scheduledDelayMillis: 500 },
        ),
      ],
    });
    fournisseur.register();

    registerInstrumentations({
      tracerProvider: fournisseur,
      instrumentations: [
        new FetchInstrumentation({
          //  L'exportateur envoie ses lots par `fetch`, que l'on instrumente :
          //  sans cette exclusion, chaque envoi produirait un span, qui
          //  produirait un envoi... Le Collector n'est pas observé.
          ignoreUrls: [prefixe(this.configuration.urlCollecteurOtlp)],
          //  Par prudence, l'instrumentation n'ajoute PAS `traceparent` aux
          //  requêtes vers une autre origine. Le service des locations est sur
          //  une autre origine (port 8080) : il faut l'autoriser nommément,
          //  sinon on obtient deux traces au lieu d'une, sans aucune erreur.
          propagateTraceHeaderCorsUrls: [
            prefixe(this.configuration.urlLocations),
          ],
        }),
      ],
    });
  }
}
