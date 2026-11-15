/**
 * L'état de l'application et ses quatre gestes.
 *
 * <p>C'est la couche application du navigateur.
 * Elle sait ce que l'utilisateur veut faire,
 * enchaîne les appels au service dorsal,
 * et traduit le résultat en état affichable.
 * Elle ne connaît pas le DOM.</p>
 *
 * <p>Les composants ne font qu'afficher ce qu'elle expose et appeler ses
 * méthodes. Aucun composant n'appelle {@link ParcApi} directement.</p>
 *
 * <p>TP3 : c'est aussi la couche qui porte l'observation du navigateur, parce
 * que c'est la seule qui sache ce que l'utilisateur a VOULU faire. Un span par
 * geste (et non par requête), et une ligne de journal structurée par geste,
 * qui porte le trace_id. Les composants n'en savent rien, et `parc-api.ts`
 * non plus : le span du `fetch` y est déjà posé par l'instrumentation.</p>
 */
import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, Observable } from 'rxjs';

import { JournalNavigateur } from '../core/observability/journal-navigateur';
import { Geste, Observability } from '../core/observability/observability';
import { Echec, echecDepuis } from './parc-errors';
import { ParcApi } from './parc-api';
import { Location, Rapport, Station } from './parc-models';

export type Resultat =
  | { readonly etat: 'vide' }
  | { readonly etat: 'succes'; readonly message: string }
  | { readonly etat: 'echec'; readonly echec: Echec };

@Injectable({ providedIn: 'root' })
export class ParcStore {
  private readonly api = inject(ParcApi);
  private readonly observability = inject(Observability);
  private readonly journal = inject(JournalNavigateur);

  private readonly _stations = signal<readonly Station[]>([]);
  private readonly _locations = signal<readonly Location[]>([]);
  private readonly _rapport = signal<Rapport | null>(null);
  private readonly _resultat = signal<Resultat>({ etat: 'vide' });
  private readonly _enCours = signal(false);
  private readonly _trace = signal('');

  readonly stations = this._stations.asReadonly();
  readonly locations = this._locations.asReadonly();
  readonly rapport = this._rapport.asReadonly();
  readonly resultat = this._resultat.asReadonly();
  readonly enCours = this._enCours.asReadonly();
  /** TP3 : trace du dernier geste, à coller dans Jaeger. */
  readonly trace = this._trace.asReadonly();

  readonly locationsEnCours = computed(() =>
    this._locations().filter((l) => l.etat === 'EN_COURS'),
  );

  // Les quatre gestes

  /** Charge les stations, puis les locations (l'état initial de la page). */
  rafraichir(): Promise<void> {
    return this.geste('geste.rafraichir', {}, async (g) => {
      const stations = await this.appeler(g, this.api.stations());
      const locations = await this.appeler(g, this.api.locations());
      this._stations.set(stations);
      this._locations.set(locations);
      const louables = stations.filter((s) => s.louable).length;
      return `${stations.length} stations, dont ${louables} louables`;
    });
  }

  louer(codeStation: string): Promise<void> {
    return this.geste('geste.louer', { 'station.code': codeStation }, async (g) => {
      const location = await this.appeler(g, this.api.ouvrir(codeStation));
      await this.recharger(g);
      return `location ${location.reference} ouverte : vélo ${location.identifiantVelo}`;
    });
  }

  rendre(reference: string, codeStation: string): Promise<void> {
    const attributs = { 'location.reference': reference, 'station.arrivee': codeStation };
    return this.geste('geste.rendre', attributs, async (g) => {
      const location = await this.appeler(g, this.api.restituer(reference, codeStation));
      await this.recharger(g);
      return (
        `location ${location.reference} clôturée : ${location.dureeMinutes} min, ` +
        `${location.montant} €`
      );
    });
  }

  /** Le geste long. */
  voirRapport(reference: string): Promise<void> {
    return this.geste('geste.voir_rapport', { 'location.reference': reference }, async (g) => {
      const rapport = await this.appeler(g, this.api.rapport(reference));
      this._rapport.set(rapport);
      return `rapport de ${rapport.reference} établi`;
    });
  }

  // l'enveloppe

  /**
   * Un geste de l'utilisateur générant un état d'attente d'un travail, un résultat,
   * et éventuellement un échec traduit.
   *
   * <p>TP3 : le geste devient un span nommé d'après lui, et produit UNE ligne
   * de journal à sa fin. Niveau : INFO s'il aboutit. WARN si le service a
   * répondu un refus (l'application continue normalement). ERROR si le
   * service est injoignable ou si l'échec est inattendu.</p>
   */
  private async geste(
    nom: string,
    attributs: Record<string, string>,
    travail: (g: Geste) => Promise<string>,
  ): Promise<void> {
    this._enCours.set(true);
    this._resultat.set({ etat: 'vide' });
    const debut = performance.now();
    await this.observability.geste(nom, attributs, async (g) => {
      const champs = () => ({
        geste: nom,
        ...attributs,
        duree_ms: Math.round(performance.now() - debut),
      });
      try {
        this._resultat.set({ etat: 'succes', message: await travail(g) });
        this.journal.info('geste.termine', g.traceId, champs());
      } catch (cause) {
        const echec = echecDepuis(cause);
        g.echec(echec.categorie, echec.statut);
        const ligne = { ...champs(), 'echec.categorie': echec.categorie, 'echec.statut_http': echec.statut };
        if (echec.categorie === 'refus-du-service') {
          this.journal.warn('geste.echoue', g.traceId, ligne);
        } else {
          this.journal.error('geste.echoue', g.traceId, ligne);
        }
        this._resultat.set({ etat: 'echec', echec });
      } finally {
        this._trace.set(g.traceId);
        this._enCours.set(false);
      }
    });
  }

  /** Recharge stations et locations après un geste qui les modifie. */
  private async recharger(g: Geste): Promise<void> {
    this._stations.set(await this.appeler(g, this.api.stations()));
    this._locations.set(await this.appeler(g, this.api.locations()));
  }

  /**
   * Souscrit à l'observable DANS le contexte du geste.
   *
   * <p>`firstValueFrom` souscrit de façon synchrone : la requête `fetch` part
   * pendant que le span du geste est actif, son span automatique en devient
   * l'enfant, et `traceparent` porte le bon parent jusqu'au service.</p>
   */
  private appeler<T>(g: Geste, source: Observable<T>): Promise<T> {
    return g.dans(() => firstValueFrom(source));
  }
}