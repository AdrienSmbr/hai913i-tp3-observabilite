/**
 * TP3, l'observabilité vue par l'application.
 *
 * <p>C'est la seule porte par laquelle une fonctionnalité parle de
 * télémétrie. Elle n'importe que `@opentelemetry/api`, en version stable
 * 1.x. Le SDK expérimental reste enfermé dans `telemetry-bootstrap.ts`.</p>
 *
 * <p>Elle offre une seule opération, {@link Observability.geste} : « ce que
 * l'utilisateur vient de faire mérite un span ». Le span est nommé d'après le
 * GESTE, jamais d'après une route : un geste peut déclencher plusieurs
 * requêtes (louer = ouvrir, puis recharger stations et locations), et elles
 * appartiennent toutes à la même trace.</p>
 */
import { Injectable } from '@angular/core';
import {
  Attributes,
  context,
  Context,
  Span,
  SpanStatusCode,
  trace,
} from '@opentelemetry/api';

/** Ce que l'application peut faire d'un geste en cours. */
export interface Geste {
  /** Identifiant de la trace du geste (32 caractères hexadécimaux). */
  readonly traceId: string;

  /**
   * Exécute un travail DANS le contexte du geste.
   *
   * <p>Le gestionnaire de contexte par défaut du navigateur
   * (StackContextManager) perd le contexte actif après un `await`. Sans
   * cette méthode, seule la première requête d'un geste serait rattachée à
   * son span. Les suivantes ouvriraient chacune une trace orpheline. Le
   * travail doit lancer sa requête dans sa portion SYNCHRONE (c'est le cas
   * de `firstValueFrom`, qui souscrit immédiatement).</p>
   */
  dans<T>(travail: () => Promise<T>): Promise<T>;

  /** Signale que le geste a échoué, sans lever : le span passe en ERROR. */
  echec(categorie: string, statut?: number): void;
}

@Injectable({ providedIn: 'root' })
export class Observability {
  /**
   * Exécute un geste de l'utilisateur dans un span nommé d'après lui.
   *
   * <p>L'instrumentation automatique sait qu'une requête HTTP a eu lieu.
   * Elle ne sait pas que l'utilisateur a cliqué sur « louer ». C'est ici que
   * cela s'écrit.</p>
   */
  async geste<T>(
    nom: string,
    attributs: Attributes,
    travail: (geste: Geste) => Promise<T>,
  ): Promise<T> {
    const span: Span = trace
      .getTracer('velos-frontend')
      .startSpan(nom, { attributes: attributs });
    const contexte: Context = trace.setSpan(context.active(), span);
    const geste: Geste = {
      traceId: span.spanContext().traceId,
      dans: (t) => context.with(contexte, t),
      echec: (categorie, statut) => {
        span.setStatus({ code: SpanStatusCode.ERROR, message: categorie });
        span.setAttribute('echec.categorie', categorie);
        if (statut !== undefined) {
          span.setAttribute('echec.statut_http', statut);
        }
      },
    };
    try {
      return await travail(geste);
    } catch (e) {
      span.recordException(e as Error);
      span.setStatus({ code: SpanStatusCode.ERROR });
      throw e;
    } finally {
      span.end();
    }
  }
}
