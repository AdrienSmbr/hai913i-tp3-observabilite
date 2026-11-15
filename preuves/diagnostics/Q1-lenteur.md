# Diagnostic Q1 : « Le rapport d'exploitation est lent. Où passe le temps ? »

Exécution du 10 novembre 2026, services fraîchement démarrés avec l'agent 2.32.0,
Collector 0.162.0, Jaeger 2.22.0.

## Preuves

Deux traces réelles, rejouables avec `node examples/lire-trace.mjs --trace <id>`.

| Trace | Origine | Durée totale | `parc.consolidation` | part |
|---|---|---|---|---|
| `4f8b14cf407bbfdbf49debc910ae2684` | clic « Rapport » (L-0005) dans l'interface | 543,7 ms | 500,6 ms | 92 % |
| `3c4246185a604959219bdfd10cf8e236` | scénario 6 (`resources/scenarios.mjs`, L-0001) | 628,1 ms | 505,1 ms | 80 % |

Répartition de la trace `4f8b14cf…` (extrait de `../traces/rapport-depuis-clic.txt`) :

```
geste.voir_rapport                         543.7 ms 100 %  velos-frontend
  GET                                      542.7 ms 100 %  velos-frontend
    GET /locations/{reference}/rapport     528.3 ms  97 %  locations
      LocationJpa.findById                   5.1 ms   1 %  locations
      GET                                  512.4 ms  94 %  locations   (client HTTP vers le parc)
        GET /stations/{code}/indice-rotation 508.9 ms 94 %  parc
          parc.indice_rotation             505.5 ms  93 %  parc
            StationJpa.findById              2.5 ms   0 %  parc   (SQL : 0,2 ms)
            VeloJpa.findByCodeStation...     1.8 ms   0 %  parc   (SQL : 0,2 ms)
            parc.consolidation             500.6 ms  92 %  parc
      LocationJpa.countByCodeDepart          4.8 ms   1 %  locations
```

Événement de journal utilisé : la ligne du navigateur
`{"message":"geste.termine","geste":"geste.voir_rapport","duree_ms":544,"trace_id":"4f8b14cf407bbfdbf49debc910ae2684"}`
donne la durée perçue par l'utilisateur et l'identifiant à coller dans Jaeger. Les journaux
dorsaux ne disent rien de ce rapport, et c'est voulu : aucune ligne n'y est nécessaire.

## Raisonnement

1. Le service interrogé (`locations`) passe 512,4 ms sur 528,3 ms dans son span CLIENT vers le parc :
   le temps n'est pas chez lui.
2. Côté `parc`, le span serveur (508,9 ms) contient `parc.indice_rotation` (505,5 ms), dont les deux
   lectures en base totalisent 4,3 ms (les requêtes SQL elles-mêmes : 0,2 ms chacune).
3. L'étape `parc.consolidation`, enfant direct de `parc.indice_rotation`, dure à elle seule 500,6 ms.

**Conclusion.** Le temps est passé dans le **service du parc**, couche **service**
(`ServiceParc.consolider`, appelé par `ServiceParc.indiceRotation`), et non dans la base de données ni
dans le réseau. Ce n'est pas « la base qui est lente » : les deux SELECT pèsent ensemble moins de 1 ms.

## Ce que l'agent seul apprenait, et ce qu'il ne pouvait pas apprendre

* **Appris sans une ligne de code** : la lenteur est dans le parc (le span client de `locations` et le
  span serveur de `parc` ont la même durée), et elle n'est pas en base (spans JDBC à 0,2 ms).
* **Pas appris** : le span serveur `GET /stations/{code}/indice-rotation` de l'agent montrait un trou de
  ~500 ms sans enfant. Seul un span manuel pouvait nommer ce trou ; c'est le rôle de
  `parc.consolidation` (annotation `@WithSpan`) et de `parc.indice_rotation` (span explicite, qui porte
  la station et le nombre de vélos rattachés).

## Nuance de mesure

Dans le scénario 6, `LocationJpa.countByCodeDepart` dure 91,9 ms (15 %), contre 4,8 ms dans la trace du
clic, exécutée 22 s plus tard. C'est la première exécution de cette requête dérivée après le démarrage
(préparation par Hibernate, compilation à la volée). On ne conclut pas sur un seul échantillon : la
part de `parc.consolidation` reste dominante dans les deux (80 % et 92 %), et sa durée est stable
(505,1 ms et 500,6 ms).
