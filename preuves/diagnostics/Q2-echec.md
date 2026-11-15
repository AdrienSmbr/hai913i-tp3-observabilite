# Diagnostic Q2 : « Une location vient d'échouer. À quelle étape, et à cause de quoi ? »

Exécution du 10 novembre 2026, même chaîne que pour Q1.

## Le cas traité : un clic « Louer » sur la Promenade du Peyrou

Le navigateur affiche `refus-du-service (HTTP 503) — station hors service : ST-PEYROU`.
Vu du navigateur, rien ne distingue encore cet échec d'une panne du service des locations.

### Les trois lignes de journal, une par processus, même `trace_id`

```
navigateur (console)
{"niveau":"WARN","message":"geste.echoue","geste":"geste.louer","station.code":"ST-PEYROU",
 "echec.categorie":"refus-du-service","echec.statut_http":503,
 "trace_id":"4be973674a1c73a6b41661b8129b0e0c"}

parc (journaux/parc.log, ligne 4)
{"log":{"level":"WARN","logger":"hai913i.tp3.parc.service.ServiceParc"},
 "message":"parc.retrait_refuse","trace_id":"4be973674a1c73a6b41661b8129b0e0c",
 "span_id":"d744b949dd12f6a2","station":{"code":"ST-PEYROU"},"refus":{"motif":"station_hors_service"}}

locations (journaux/locations.log, ligne 16)
{"log":{"level":"ERROR","logger":"hai913i.tp3.locations.service.ServiceLocations"},
 "message":"location.ouverture_echouee","trace_id":"4be973674a1c73a6b41661b8129b0e0c",
 "span_id":"ab06c905ccace663","echec":{"etape":"retrait_parc","cause":"ParcIndisponible"},
 "station":{"depart":"ST-PEYROU"}}
```

### La trace `4be973674a1c73a6b41661b8129b0e0c` (11 spans, 3 services, 19,3 ms)

```
geste.louer                       19.3 ms  velos-frontend  ERREUR
  POST                            18.6 ms  velos-frontend  ERREUR
    POST /locations               15.5 ms  locations       ERREUR
      location.ouvrir             12.1 ms  locations       ERREUR   span_id ab06c905ccace663
        POST                       8.9 ms  locations       ERREUR   (client HTTP vers le parc)
          POST /stations/{code}/retrait 6.9 ms parc         ERREUR
            parc.retirer_velo      3.6 ms  parc            ERREUR   span_id d744b949dd12f6a2
              StationJpa.findById  1.1 ms  parc            (SELECT parc.station : 0,2 ms)
```

## Raisonnement

1. `location.ouvrir` porte `echec.etape=retrait_parc` : l'abonné a été vérifié (étape 1 franchie),
   l'échec survient à l'étape 2, l'appel au parc. Ce n'est donc ni une faute d'authentification (401),
   ni un échec d'enregistrement local.
2. Le span client et le span serveur du parc existent et sont en erreur : le parc **a répondu**. Ce
   n'est pas une panne réseau (comparer avec le scénario S11 ci-dessous).
3. Sous le span serveur du parc, `parc.retirer_velo` est en erreur, et la ligne WARN qui porte son
   `span_id` (`d744b949dd12f6a2`) donne le motif : `station_hors_service`. Une seule requête SQL a eu
   lieu (lecture de la station) : la décision a été prise avant même de chercher un vélo.
4. Le service des locations a traduit la réponse 503 du parc en `ParcIndisponible`, puis en 503 vers le
   navigateur.

**Conclusion.** L'échec a lieu à l'étape **retrait du vélo**. La **cause vient du service du parc**,
qui refuse le retrait parce que la station `ST-PEYROU` est hors service. Le service des locations n'a
commis aucune faute : il a vérifié l'abonné puis relayé le refus. Sa ligne est en ERROR parce qu'il n'a
pas pu rendre le service demandé à cause d'une dépendance indisponible, alors que le parc, qui a
appliqué sa règle, journalise un simple WARN.

## Les quatre échecs qui « se ressemblent vus du navigateur »

| Scénario | Code | `echec.etape` | `echec.cause` | Ligne parc | Qui est en cause |
|---|---|---|---|---|---|
| 3. station sans vélo | 409 | `retrait_parc` | `MouvementRefuse` | WARN `aucun_velo_disponible` | parc (refus métier) |
| 4. station hors service | 503 | `retrait_parc` | `ParcIndisponible` | WARN `station_hors_service` | parc (indisponibilité) |
| S8. station inconnue | 404 | `retrait_parc` | `StationInconnue` | WARN `station_inconnue` | parc (code inconnu) |
| 5. abonné refusé | 401 | `verification_abonne` | `AbonneRefuse` | aucune, pas de span parc | locations (abonné) |
| S11. parc arrêté | 503 | `retrait_parc` | `ParcIndisponible` + pile `ConnectException` | aucune, pas de span parc | parc injoignable |

Le cas S11 (trace `4e3a1363ac9bce0c23e85b1f7e378372`, 3 spans, 1 seul service) donne le même code 503
que le scénario 4 : seule la trace (pas de span serveur `parc`) et la pile dans la ligne ERROR
(`service du parc injoignable : http://localhost:8081`) distinguent une panne d'un refus.
