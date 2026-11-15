/**
 * Les fournisseurs de l'application.
 *
 * <ol>
 *   <li><b>la configuration</b> est un jeton injectable, pas une constante
 *       recopiée dans le code des fonctionnalités ;</li>
 *   <li><b>HttpClient</b> est fourni avec <code>withFetch()</code> : le
 *       transport est donc <code>fetch</code>, et non
 *       <code>XMLHttpRequest</code>.</li>
 *   <li>TP3 : <b>la télémétrie démarre avant l'application</b>, par un
 *       initialiseur.</li>
 * </ol>
 *
 * <p>TP3 : <code>withFetch()</code> est désormais indispensable à
 * l'observabilité. L'instrumentation retenue n'observe que <code>fetch</code> :
 * sans cette option, tout fonctionnerait et la trace de bout en bout
 * disparaîtrait, silencieusement.</p>
 */
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';

import { CONFIGURATION, configurationParDefaut } from './core/configuration';
import { TelemetryBootstrap } from './core/observability/telemetry-bootstrap';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    { provide: CONFIGURATION, useFactory: () => configurationParDefaut() },
    provideHttpClient(withFetch()),
    //  Avant le premier rendu, donc avant la première requête HTTP.
    provideAppInitializer(() => inject(TelemetryBootstrap).demarrer()),
  ],
};
