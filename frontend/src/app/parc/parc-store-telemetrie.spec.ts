/**
 * TP3, l'observation des gestes, testée sans Collector ni Jaeger.
 *
 * <p>`Observability` et `JournalNavigateur` sont remplacés par des doubles de
 * quelques lignes. On vérifie ce que la couche application DEMANDE : un span
 * par geste, nommé d'après le geste, toutes les requêtes du geste exécutées
 * dans son contexte, un échec signalé, et une ligne de journal qui porte le
 * trace_id. Le comportement d'OpenTelemetry lui-même n'est pas testé ici.</p>
 */
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { JournalNavigateur } from '../core/observability/journal-navigateur';
import { Geste, Observability } from '../core/observability/observability';
import { ParcApi } from './parc-api';
import { ParcStore } from './parc-store';
import { Location } from './parc-models';

const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';

const EN_COURS: Location = {
  reference: 'L-0001',
  identifiantVelo: 'V-0006',
  codeDepart: 'ST-COMEDIE',
  codeArrivee: null,
  dureeMinutes: 0,
  montant: null,
  etat: 'EN_COURS',
};

interface Enregistrement {
  gestes: { nom: string; attributs: Record<string, unknown> }[];
  dansContexte: number;
  echecs: { categorie: string; statut?: number }[];
  lignes: { niveau: string; evenement: string; traceId: string; champs: Record<string, unknown> }[];
}

function configurer(api: Partial<ParcApi>) {
  const e: Enregistrement = { gestes: [], dansContexte: 0, echecs: [], lignes: [] };
  const observability = {
    geste: async <T>(
      nom: string,
      attributs: Record<string, unknown>,
      travail: (g: Geste) => Promise<T>,
    ) => {
      e.gestes.push({ nom, attributs });
      return travail({
        traceId: TRACE,
        dans: (t) => {
          e.dansContexte += 1;
          return t();
        },
        echec: (categorie, statut) => e.echecs.push({ categorie, statut }),
      });
    },
  };
  const ligne =
    (niveau: string) =>
    (evenement: string, traceId: string, champs: Record<string, unknown> = {}) =>
      e.lignes.push({ niveau, evenement, traceId, champs });
  const journal = { info: ligne('info'), warn: ligne('warn'), error: ligne('error') };

  TestBed.configureTestingModule({
    providers: [
      { provide: ParcApi, useValue: api },
      { provide: Observability, useValue: observability },
      { provide: JournalNavigateur, useValue: journal },
    ],
  });
  return { store: TestBed.inject(ParcStore), e };
}

describe('ParcStore — télémétrie', () => {
  it('fait d une location UN geste, et y rattache ses trois requetes', async () => {
    const { store, e } = configurer({
      ouvrir: () => of(EN_COURS),
      stations: () => of([]),
      locations: () => of([EN_COURS]),
    });

    await store.louer('ST-COMEDIE');

    expect(e.gestes).toEqual([{ nom: 'geste.louer', attributs: { 'station.code': 'ST-COMEDIE' } }]);
    expect(e.dansContexte).toBe(3);
    expect(e.lignes).toHaveLength(1);
    expect(e.lignes[0]).toMatchObject({ niveau: 'info', evenement: 'geste.termine', traceId: TRACE });
    expect(store.trace()).toBe(TRACE);
  });

  it('signale un refus du service et journalise en WARN', async () => {
    const { store, e } = configurer({
      ouvrir: () =>
        throwError(
          () => new HttpErrorResponse({ status: 503, error: { detail: 'station hors service' } }),
        ),
    });

    await store.louer('ST-PEYROU');

    expect(e.echecs).toEqual([{ categorie: 'refus-du-service', statut: 503 }]);
    expect(e.lignes[0]).toMatchObject({ niveau: 'warn', evenement: 'geste.echoue', traceId: TRACE });
    expect(e.lignes[0].champs['echec.statut_http']).toBe(503);
  });

  it('journalise un service injoignable en ERROR', async () => {
    const { store, e } = configurer({
      stations: () => throwError(() => new HttpErrorResponse({ status: 0 })),
    });

    await store.rafraichir();

    expect(e.echecs[0].categorie).toBe('injoignable');
    expect(e.lignes[0]).toMatchObject({ niveau: 'error', evenement: 'geste.echoue' });
  });

  it('ne met jamais le mot de passe ni l adresse dans une ligne de journal', async () => {
    const { store, e } = configurer({
      ouvrir: () => of(EN_COURS),
      stations: () => of([]),
      locations: () => of([EN_COURS]),
    });

    await store.louer('ST-COMEDIE');

    const tout = JSON.stringify(e.lignes) + JSON.stringify(e.gestes);
    expect(tout).not.toContain('Zx9!Vole');
    expect(tout).not.toContain('camille.rey');
    expect(tout).not.toContain('motDePasse');
  });
});
