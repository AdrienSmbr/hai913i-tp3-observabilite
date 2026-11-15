/**
 * Configuration de l'application désignant un seul endroit pour les constantes
 * d'infrastructure.
 *
 * <p>Aucune URL ne doit être écrite en dur dans le code d'une
 * fonctionnalité.</p>
 *
 * <p>Le mécanisme recommandé par Angular aujourd'hui :
 * un <code>InjectionToken</code> fourni par l'application.</p>
 *
 * <p>TP3 : on y ajoute l'adresse du Collector. Quand on se demande « où part
 * la télémétrie du navigateur ? », c'est ce fichier qu'on ouvre.</p>
 */
import { InjectionToken } from '@angular/core';

export interface ConfigurationApplication {
  /**
   * Origine du service des locations (le seul service que l'on appelle).
   * Sert aussi à AUTORISER la propagation de `traceparent` vers lui.
   */
  readonly urlLocations: string;

  /** TP3 : où part la télémétrie du navigateur (le Collector, OTLP/HTTP). */
  readonly urlCollecteurOtlp: string;

  /** Identité de cette application. C'est son nom de service dans Jaeger. */
  readonly nomService: string;

  readonly versionService: string;
}

export const CONFIGURATION = new InjectionToken<ConfigurationApplication>(
  'configuration de l application',
);

export function configurationParDefaut(): ConfigurationApplication {
  return {
    urlLocations: 'http://localhost:8080',
    urlCollecteurOtlp: 'http://localhost:4318/v1/traces',
    nomService: 'velos-frontend',
    versionService: '1.0.0',
  };
}
