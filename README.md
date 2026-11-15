# velos-tp3 observable, HAI913I TP 3

Adrien SAMBARREY et Pierre PREVOST-AVENEL, Master 2 GL, Université de Montpellier, 2026-2027.

Dépôt : https://github.com/AdrienSmbr/hai913i-tp3-observabilite (privé)

On part de l'application velos-tp3 fournie (deux services Spring Boot et une interface Angular) et on la rend observable. Les services écrivent des journaux structurés, une trace suit chaque requête du clic jusqu'à la requête SQL, et chaque ligne de journal porte l'identifiant de sa trace. Le comportement fonctionnel n'a pas changé : les 21 contrôles de `resources/verifier-fonctionnel.mjs` passent avec et sans agent.

Toutes les commandes se lancent depuis la racine `velos-tp3/`, sauf mention contraire.

## 1. Projet

### 1.1 Les trois composants

| Processus | Port | Nom de service (Jaeger) | Rôle |
|---|---|---|---|
| `frontend` | 4200 | `velos-frontend` | interface Angular, ne parle qu'au service des locations |
| `locations` | 8080 | `locations` | registre des locations, abonnés et facturation, seul point d'entrée du navigateur |
| `parc` | 8081 | `parc` | stations et vélos, appelé uniquement par `locations` |

L'infrastructure d'observabilité tourne dans Docker. Un OpenTelemetry Collector écoute sur 4317 (gRPC) et 4318 (HTTP), Jaeger sur 16686. Tout ce qui va à Jaeger passe par le Collector.

```
navigateur --HTTP + traceparent--> locations --HTTP + traceparent--> parc --JDBC--> H2
    |  OTLP/HTTP :4318                 |  OTLP/gRPC :4317                 |  OTLP/gRPC :4317
    +-------------------------> Collector <-----------------------------+
                                    |  OTLP
                                    v
                              Jaeger :16686

journaux : locations, parc -> journaux/<service>.log (JSON ECS) + console
           navigateur      -> console du navigateur
```

### 1.2 Ce que nous avons modifié

| Fichier | Modification |
|---|---|
| `parc/pom.xml`, `locations/pom.xml` | BOM OpenTelemetry 1.66.0, `opentelemetry-api` et `opentelemetry-instrumentation-annotations` 2.32.0, plus `opentelemetry-sdk-testing` en test pour locations |
| `*/configuration/ConfigurationTelemetrie.java` | nouveau, publie le `Tracer` de chaque service (sans effet sans agent) |
| `locations/.../service/ServiceLocations.java` | spans `location.ouvrir` et `location.restituer`, événement de span `location.facturee`, journaux `location.ouverte`, `location.cloturee`, `location.ouverture_echouee` et `location.restitution_echouee` |
| `parc/.../service/ServiceParc.java` | span `parc.indice_rotation`, annotations `@WithSpan` pour `parc.consolidation` et `parc.retirer_velo`, journal `parc.retrait_refuse` |
| `locations/.../configuration/ConfigurationCors.java` | autorise les en-têtes `traceparent` et `tracestate` pour que la trace passe du navigateur au service |
| `*/src/main/resources/application.properties` | niveaux, motif console avec `trace_id` et `span_id`, fichier JSON ECS `journaux/<service>.log` |
| `frontend/src/app/core/observability/*` | nouveau. `telemetry-bootstrap.ts` est le seul fichier qui importe le SDK expérimental, `observability.ts` crée le span d'un geste, `journal-navigateur.ts` écrit le journal structuré avec le `trace_id` |
| `frontend/src/app/parc/parc-store.ts` | un span et une ligne de journal par geste de l'utilisateur |
| `frontend/src/app/parc/parc-page.*` | affiche l'identifiant de trace du dernier geste |
| `frontend/src/app/app.config.ts`, `core/configuration.ts` | démarrage de la télémétrie et adresse du Collector |
| `observabilite/` | nouveau, contient `docker-compose.yml` et `otel-collector-config.yaml` |
| `scripts/demarrer-observe.mjs` | nouveau, lance un service avec l'agent Java |
| `scripts/scenarios-supplementaires.mjs`, `scripts/bilan-q3.mjs` | nouveaux, scénarios S8 à S11 et réponse à Q3 depuis le journal |
| tests | `ServiceLocationsTelemetrieTest.java` (4 tests), `parc-store-telemetrie.spec.ts` (4 tests) |

Les contrôleurs, les règles métier, la facturation et les écrans se comportent comme avant.

### 1.3 Limites connues

* OpenTelemetry déclare expérimentale l'instrumentation du navigateur. Toute sa configuration tient donc dans `frontend/src/app/core/observability/telemetry-bootstrap.ts`.
* Le journal du navigateur reste dans la console du navigateur. Il n'est ni centralisé ni conservé, et les lignes citées dans le compte-rendu ont été recopiées depuis la console.
* Jaeger garde les traces en mémoire. Elles disparaissent quand le conteneur s'arrête.
* Le chargement initial du parc (`DonneesInitiales`, au démarrage) produit des traces sans requête HTTP. Elles ne servent à aucune question et on les ignore.
* Aucune métrique n'est émise (bonus D3 et D4 non traités).

## 2. Environnement

Versions utilisées pour produire les preuves du compte-rendu.

| Élément | Version |
|---|---|
| Système | macOS 27.2 (Apple Silicon) |
| JDK | OpenJDK 17.0.16 (Homebrew) |
| Maven | 3.9.11 |
| Spring Boot | 4.1.1 (fixé par les `pom.xml` fournis) |
| Node.js / npm | 26.13.0 / 11.20.0. Angular 21 déclare `^20.19 \|\| ^22.12 \|\| ^24`, Node 26 a fonctionné quand même (voir §8) |
| Docker / Compose | Docker 29.9.2 (Docker Desktop 4.97.0) / Compose v5.6.1 |
| Agent Java OpenTelemetry | 2.32.0 |
| OpenTelemetry Collector | 0.162.0 (`otel/opentelemetry-collector`) |
| Jaeger | 2.22.0 (`jaegertracing/jaeger`) |
| OpenTelemetry Java | `opentelemetry-api` 1.66.0 (BOM), `opentelemetry-instrumentation-annotations` 2.32.0, `opentelemetry-sdk-testing` 1.66.0 (tests) |
| OpenTelemetry JS | `@opentelemetry/api` 1.9.1, `sdk-trace-web` 2.12.0, `sdk-trace-base` 2.12.0, `resources` 2.12.0, `browser-instrumentation` 0.8.1 (expérimental), `exporter-trace-otlp-http` 0.223.0, `instrumentation` 0.223.0, `semantic-conventions` 1.43.0 |
| Éditeur | pas d'IDE. Éditeur de texte et outils en ligne de commande (`mvn`, `java`, `npm`, `node`, `docker`), avec l'assistant Claude Code (voir la section IA du compte-rendu) |

## 3. Construction, depuis une copie fraîche

```
node resources/telecharger-agent.mjs
mvn -f parc/pom.xml clean package
mvn -f locations/pom.xml clean package
cd frontend && npm ci && cd ..
```

La première commande dépose `opentelemetry-javaagent.jar` (25 Mo, version 2.32.0) à la racine. Il n'est pas versionné. `mvn package` lance aussi les tests.

## 4. Démarrage

L'ordre compte. D'abord l'infrastructure, puis le parc, puis les locations (qui appellent le parc), enfin l'interface. Prévoir un terminal par processus, `Ctrl-C` arrête chacun.

```
# 1. infrastructure (en arrière-plan)
docker compose -f observabilite/docker-compose.yml up -d

# terminal 2 : le parc, avec l'agent
node scripts/demarrer-observe.mjs parc

# terminal 3 : les locations, avec l'agent
node scripts/demarrer-observe.mjs locations

# terminal 4 : l'interface web
cd frontend && npm start
```

Ouvrir ensuite http://localhost:4200 pour l'application et http://localhost:16686 pour Jaeger.

| Port | Processus |
|---|---|
| 4200 | interface Angular (`ng serve`) |
| 8080 | service `locations` |
| 8081 | service `parc` |
| 4317 | Collector, OTLP/gRPC (agents Java) |
| 4318 | Collector, OTLP/HTTP (navigateur, CORS limité à `http://localhost:4200`) |
| 16686 | Jaeger, interface et API |

`demarrer-observe.mjs` lance le même JAR que `scripts/demarrer.mjs` en ajoutant `-javaagent:opentelemetry-javaagent.jar`, `-Dotel.service.name=<parc|locations>`, `-Dotel.exporter.otlp.endpoint=http://localhost:4317`, `-Dotel.exporter.otlp.protocol=grpc`, `-Dotel.metrics.exporter=none` et `-Dotel.logs.exporter=none`. Lancée sans agent (`node scripts/demarrer.mjs parc`), l'application fonctionne pareil et n'émet aucune trace.

Pour arrêter l'infrastructure : `docker compose -f observabilite/docker-compose.yml down`.

## 5. Scénarios

Sur des services qu'on vient de démarrer :

```
node resources/scenarios.mjs
node scripts/scenarios-supplementaires.mjs
```

La première commande joue les sept scénarios fournis, la seconde nos trois scénarios. Chacun a un but d'observation :

| Scénario | But d'observation | Où regarder |
|---|---|---|
| S8. station inconnue | le 4e échec de Q2 (404), un refus venu du parc | `parc.log`, événement `parc.retrait_refuse` avec le motif `station_inconnue`, span `parc.retirer_velo` en erreur |
| S9. double restitution | un échec 409 entièrement local, la trace du second rendu n'a aucun span `parc` | `locations.log`, événement `location.restitution_echouee` à l'étape `recherche_location` |
| S10. activité pour Q3 | deux ouvertures à deux autres stations, puis leurs clôtures | `location.ouverte` et `location.cloturee`, puis `node scripts/bilan-q3.mjs` |

Un quatrième scénario demande d'arrêter le parc (`Ctrl-C` dans son terminal) en laissant les locations démarrées :

```
node scripts/scenarios-supplementaires.mjs --parc-arrete
```

| Scénario | But d'observation | Où regarder |
|---|---|---|
| S11. parc injoignable | une panne et non un refus, 503 sans réponse du parc | ligne ERROR avec la pile (`ConnectException`), trace de 3 spans sans service `parc` |

La réponse à Q3 se calcule à partir du seul journal structuré :

```
node scripts/bilan-q3.mjs
```

Depuis le navigateur, l'interface affiche la trace du dernier geste sous le message de résultat. Nos preuves viennent des gestes « Louer » (Place de la Comédie), « Rapport », « Rendre à ST-ARCEAUX » puis « Louer » sur la Promenade du Peyrou, qui échoue.

## 6. Observabilité

### 6.1 Où sont les journaux

| Source | Où | Format |
|---|---|---|
| `locations` | `journaux/locations.log` et la console | fichier en JSON ECS, un objet par ligne. Console en ligne lisible |
| `parc` | `journaux/parc.log` et la console | idem |
| navigateur | console des outils de développement (F12) | un objet JSON par ligne |

Le dossier `journaux/` est relatif au dossier de lancement (la racine). On peut le changer avec `-Dvelos.journaux=<dossier>`. Chaque ligne écrite pendant une requête porte `trace_id` et `span_id`.

### 6.2 Retrouver une trace

* Depuis le navigateur, copier l'identifiant affiché sous « trace du dernier geste » et le coller dans le champ Lookup by Trace ID de Jaeger.
* Depuis un journal, prendre le champ `trace_id` de la ligne.
* En ligne de commande, `node examples/lire-trace.mjs --trace <trace_id>` imprime l'arbre et les durées. `node examples/lire-trace.mjs --service locations` donne la dernière trace d'un service.

### 6.3 Rejouer la corrélation journal ↔ trace

```
node examples/relier-journal-trace.mjs --journal journaux/locations.log
node examples/relier-journal-trace.mjs --journal journaux/locations.log --trace <trace_id>
node examples/lire-trace.mjs --trace <trace_id>
```

La ligne `location.ouverte` (ou `location.ouverture_echouee`) porte le `span_id` du span `location.ouvrir` de la même trace.

### 6.4 Rejouer les deux diagnostics

* Q1, la lenteur. Cliquer « Rapport » sur une location (ou lancer le scénario 6), puis `node examples/lire-trace.mjs --trace <trace_id>`. `parc.consolidation` prend environ 500 ms.
* Q2, l'échec. Cliquer « Louer » sur la Promenade du Peyrou (ou lancer le scénario 4). Lancer ensuite `relier-journal-trace.mjs` sur `journaux/locations.log` et sur `journaux/parc.log` avec le même `trace_id`, puis `lire-trace.mjs` sur ce `trace_id`.

## 7. Validation

```
mvn -f parc/pom.xml test             # 9 tests
mvn -f locations/pom.xml test        # 19 tests (15 fournis + 4 TP3)
cd frontend && npm test && cd ..     # 19 tests (15 fournis + 4 TP3)
node resources/verifier-fonctionnel.mjs            # sur des services qu'on vient de démarrer : 21 contrôles
node resources/verifier-confidentialite.mjs --journaux journaux   # journaux + spans Jaeger
```

| Point de contrôle | Comment le vérifier |
|---|---|
| A0 | `verifier-fonctionnel.mjs` annonce OK sur 21 contrôles. L'application d'origine n'imprimait que la ligne de `demarrer.mjs` |
| A2 | le scénario 2 écrit une ligne `location.ouverte`, le scénario 3 un WARN, le scénario 4 un ERROR |
| A3 | `verifier-confidentialite.mjs` annonce OK |
| B1 | Jaeger connaît `parc` et `locations`. La trace du scénario 7 compte 34 spans, dont 33 produits par l'agent |
| B2 | spans `location.ouvrir`, `location.restituer`, `parc.indice_rotation`, `parc.consolidation`, `parc.retirer_velo` et `geste.*` |
| B3 | un clic « Rapport » donne une trace sur 3 services, de `geste.voir_rapport` jusqu'au `SELECT parc.velo` |
| B4 | voir §6.3 |
| C1 | voir §5 |
| C3 | ce tableau, rejoué sur une copie fraîche construite avec le §3 |

## 8. Dépannage

| Symptôme | Cause probable | Remède |
|---|---|---|
| Deux traces au lieu d'une (navigateur et services séparés), sans erreur | `traceparent` retiré par le CORS, ou jamais ajouté par l'instrumentation | vérifier `allowedHeaders` dans `ConfigurationCors` et `propagateTraceHeaderCorsUrls` dans `telemetry-bootstrap.ts` |
| La trace du navigateur s'arrête au clic | `withFetch()` retiré de `app.config.ts` | le remettre, l'instrumentation n'observe que `fetch` |
| Après la première requête d'un geste, les suivantes partent dans des traces isolées | souscription faite hors de `Geste.dans` | passer par `appeler(g, ...)` dans `parc-store.ts` |
| Rien du navigateur dans Jaeger | CORS du Collector | `allowed_origins` dans `observabilite/otel-collector-config.yaml` |
| Rien du tout dans Jaeger | Collector arrêté, ou agent non chargé | `docker compose -f observabilite/docker-compose.yml logs -f collector`, et chercher `opentelemetry-javaagent - version: 2.32.0` dans la sortie du service |
| `trace_id=` vide dans les journaux | service lancé sans agent, ou ligne écrite hors requête | `node scripts/demarrer-observe.mjs` |
| `agent absent` | agent pas téléchargé | `node resources/telecharger-agent.mjs` |
| `le port 8080 est deja occupe` | une ancienne JVM tourne encore | `lsof -i :8080` puis `kill <pid>` (sous Windows : `netstat -ano \| findstr :8080`, puis `taskkill /F /PID <pid>`) |
| `verifier-fonctionnel.mjs` échoue après des scénarios | l'état du parc a changé | redémarrer les deux services (les bases sont en mémoire) |
| Docker Desktop affiche « unable to start » | le disque de Docker est sur un support absent (disque externe) | rebrancher le disque, ou changer l'emplacement dans les réglages de Docker Desktop |
| `npm` signale un Node non pris en charge | Node hors de `^20.19 \|\| ^22.12 \|\| ^24` | préférer Node 22 ou 24. Node 26 a marché chez nous |
| Le premier `npm test` échoue au bout de 60 s | le lanceur Vitest démarre lentement la première fois | relancer `npm test` |

## 9. Bonus

Non traités.
