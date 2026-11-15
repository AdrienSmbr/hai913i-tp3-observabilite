/**
 * TP3, le journal du navigateur.
 *
 * <p>Les journaux OpenTelemetry en JavaScript sont encore en développement.
 * On journalise donc dans la console, mais en STRUCTURÉ : un nom d'événement
 * stable et des champs typés, dans un seul objet JSON par ligne.</p>
 *
 * <p>Côté Java, l'agent dépose `trace_id` dans le MDC de SLF4J sans que
 * personne ne l'écrive. Ici, personne ne le fait : l'appelant le PASSE,
 * parce qu'un événement est presque toujours écrit après un `await`, quand le
 * contexte actif a déjà été dépilé.</p>
 *
 * <p>Où l'on journalise : dans la couche application (`parc-store.ts`). Pas
 * dans les composants (afficher n'est pas un événement), ni dans l'accès au
 * service (le span du `fetch` dit déjà tout de la requête).</p>
 */
import { Injectable } from '@angular/core';

type Niveau = 'info' | 'warn' | 'error';

@Injectable({ providedIn: 'root' })
export class JournalNavigateur {
  info(evenement: string, traceId: string, champs: Record<string, unknown> = {}): void {
    this.ecrire('info', evenement, traceId, champs);
  }

  warn(evenement: string, traceId: string, champs: Record<string, unknown> = {}): void {
    this.ecrire('warn', evenement, traceId, champs);
  }

  error(evenement: string, traceId: string, champs: Record<string, unknown> = {}): void {
    this.ecrire('error', evenement, traceId, champs);
  }

  private ecrire(
    niveau: Niveau,
    evenement: string,
    traceId: string,
    champs: Record<string, unknown>,
  ): void {
    console[niveau](
      JSON.stringify({
        niveau: niveau.toUpperCase(),
        service: 'velos-frontend',
        message: evenement,
        ...champs,
        trace_id: traceId,
      }),
    );
  }
}
