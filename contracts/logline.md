# Contract: de logregel (versie 0.5.0)

De afspraak tussen de squads: de Pipeline (`fcd-pipeline`) en de producten van een showcase (zoals de meting van het key result in `sk-demo`) sturen logregels; het Dashboard van die showcase (`fcd-dashboard`) ontvangt ze. Elke showcase heeft een eigen Dashboard; een logregel van een andere showcase wordt geweigerd. Contract first: eerst dit contract wijzigen, met een nieuwe versie, dan de code.

- **Formaat:** een CDEvent (CD Foundation, spec 0.4), JSON. De velden van FCD staan in `customData`.
- **Contract:** het JSON Schema [logline.schema.json](logline.schema.json) (draft-07, versie in `x-version`), geregistreerd in Apicurio Registry met de regel BACKWARD. Het eindpunt: `POST /api/v1/events` van het Dashboard van de showcase, zie [logline.openapi.yaml](logline.openapi.yaml).
- **Idempotent:** dezelfde `context.id` nog eens sturen telt één keer.
- **Geen Dashboard bereikbaar:** de Pipeline zet de logregel in een outbox (`~/.fcd/outbox.jsonl`) en stuurt hem later.

## Velden

| Veld | Waar | Betekenis |
|---|---|---|
| tijd | `context.timestamp` | Wanneer de activiteit begon (ISO 8601, UTC) |
| duur | `customData.durationSeconds` | Hoe lang de activiteit duurde, in seconden |
| wie triggert | `customData.trigger` | `kind`: `human`, `ai-agent`, `automated-update`; bij het key result `guest` (een echte gast) of `test` (een bestelling van een end-to-end test, telt niet mee); `name` |
| deelproduct | `customData.product`, `customData.subProduct` | Product, deelproduct met versie, en de versies van de containers |
| actie | `context.type` + `subject.content` | Welke stap of gate, en de uitkomst |
| run | `customData.run` | De run van de pipeline |
| commit | `customData.commit` | `sha` en `time`: voor de doorlooptijd (DORA) |
| showcase, experiment | `customData.showcase` (`demo`, `classic`, `ideal`, of `fcd` voor FCD zelf), `customData.experiment` | Bijvoorbeeld `demo`, `mvp0` (gebruik letters, cijfers, `.`, `_` en `-`). Elk experiment begint met een lege log. |
| omgeving | `customData.environment` | `werkplek` (vóór een push) of `lokaal` (de uitgerolde omgeving; telt voor DORA) |
| soort release | `customData.releaseType` | `planned` of `emergency` (noodpatch) |

## Soorten logregels

| `context.type` | Wanneer | `subject.content` |
|---|---|---|
| `dev.cdevents.pipelinerun.started.0.2.0` / `.finished` | Begin en eind van een run | `pipelineName`, `outcome` (`running`, `success`, `failure`), `errors`, `warnings`; bij `started` optioneel `steps` (de namen van de stappen van deze run, in volgorde) |
| `dev.cdevents.taskrun.started.0.2.0` / `.finished` | Begin en eind van elke CI-stap | `taskName`, `step` (1–10), `title`, `outcome` (`running`, `succeeded`, `failed`, `skipped`), `reason`; bij `finished` en `failed` optioneel `excerpt` (zie onder); optioneel `container` bij een stap per container (zie onder) |
| `dev.cdevents.testsuiterun.started.0.2.0` | Begin van een testsoort | `testSuite` (`id`, `name`), `testType`, optioneel `expected` (aantal tests, als bekend) |
| `dev.cdeventsx.fcd-testsuiterun.progressed.0.1.0` | Tussenstand van een testsoort, hoogstens één per seconde | `testSuite`, `testType`, `tests`, `passed`, `failed`, `skipped` (oplopend: de stand tot nu) |
| `dev.cdevents.testsuiterun.finished.0.2.0` | Na unit-, integratie- en end-to-end tests | `testType`, `outcome` (`pass`, `fail`), `tests`, `passed`, `failed`, `skipped`, `seconds`, `failures` (namen); optioneel `container` |
| `dev.cdeventsx.fcd-qualitygate.started.0.1.0` | Begin van een quality gate | `gate`, `afterSteps` of `beforeSteps`, optioneel `waitingFor` (`kind`: `human`; `name`, `action`) als de gate op een mens wacht |
| `dev.cdeventsx.fcd-qualitygate.evaluated.0.1.0` | Einde van een quality gate (de beslissing) | `gate` (`code`, `build`, `security`, `deploy`, `human`), `afterSteps`, `outcome` (`open`, `closed`), `reason`, `explain` (id's van gebruikte afwijkingen), `explainDetails` (per afwijking: `id`, `gate`, `rule`, `reason`, `approver`, `until`) |
| `dev.cdevents.service.deployed.0.2.0` | Na een uitrol | `environment.id`, `artifactId`; optioneel `changelog`: de changelog van het deelproduct tot en met de uitgerolde versie (Markdown, hoogstens 16 000 tekens), en `alongside`: de containers die meedraaien maar geen deel van het deelproduct zijn (zoals stubs), met hun versie |
| `dev.cdevents.artifact.published.0.2.0` | Na een release | `tag`, `releaseType` |
| `dev.cdeventsx.fcd-demo.reset.0.1.0` | "Demo opnieuw": de demo staat weer op de start (eerste logregel van een nieuwe, lege log) | `startTag`, `saved` (of de vorige run en het statusrapport bewaard zijn), `branches` (de klaarstaande wijzigingen: `repository`, `branch`); `run` is leeg |
| `dev.cdeventsx.fcd-demo.merged.0.1.0` | Een klaarstaande wijziging is lokaal gemerged (knop of terminal) | `repository`, `branch`; `trigger` is wie merget; `run` is leeg |
| `dev.cdeventsx.fcd-keyresult.measured.0.1.0` | Per bestelling (sk-demo) | `keyResult`, `orderId`, `tableNumber`, `receivers` (per stub: `arrived`, `receivedAt`), `complete` |

## Begin en einde

Per stap en per gate een logregel bij het begin en bij het einde, zodat het Dashboard live kan laten zien wat de pipeline doet.

| Wat | Begin | Einde |
|---|---|---|
| Run | `pipelinerun.started` | `pipelinerun.finished` |
| CI-stap | `taskrun.started` | `taskrun.finished` |
| Testsoort | `testsuiterun.started`, daarna `fcd-testsuiterun.progressed` | `testsuiterun.finished` |
| Quality gate | `fcd-qualitygate.started` | `fcd-qualitygate.evaluated` |

- **Goedkeuring:** wacht de gate op een mens, dan staat dat in `waitingFor` van `fcd-qualitygate.started`. Bij de goedkeuring "merge op main" stuurt de werkplek-run die logregel als alle automatische gates open zijn; de gate wacht tot er een lokale run is.
- **`excerpt`:** één regel uit de uitvoer van een mislukte stap, hoogstens 300 tekens, zonder regeleinden. De afzender maskeert geheimen; het Dashboard toont hem als tekst, nooit als HTML.
- **Achterwaarts compatibel:** een Dashboard met 0.4.0 leest logregels van 0.3.x. Zonder de nieuwe logregels toont het Dashboard minder (geen tussenstand, geen wachtende goedkeuring). Stuur de nieuwe soorten pas naar een Dashboard dat 0.4.0 kent: een ouder Dashboard weigert ze.

## Stappen per container

Een container met dezelfde versie en dezelfde code als wat draait, slaat zijn stappen over: `taskrun.finished` met `outcome` `skipped` en `unchanged: true`. Het Dashboard toont zo'n stap groen ("al groen"): hij was bij die versie al gelukt.

Een stap met `level: container` in de configuratie (statische controle, unittests, contracttest, bouwen) draait per container van het deelproduct. Dan staat de container in `subject.content.container` van `taskrun.started`, `taskrun.finished` en de testlogregels. Zonder `container` geldt de stap voor het hele deelproduct. Zo laat het Dashboard per container een eigen rij zien.

## Voorbeeld: de goedkeuring wacht

```json
{
  "context": { "version": "0.4.1", "id": "5c0e2f7a-9d3b-4c1e-8f6a-0b2d4e6f8a10", "source": "fcd-pipeline/0.6.0",
               "type": "dev.cdeventsx.fcd-qualitygate.started.0.1.0", "timestamp": "2026-11-26T09:16:40Z" },
  "subject": { "id": "human-gate", "source": "sk-demo",
               "content": { "gate": "human", "beforeSteps": "release",
                            "waitingFor": { "kind": "human", "name": "sim007", "action": "merge op main met een hogere versie" } } },
  "customData": { "durationSeconds": 0, "run": "sk-demo-20261126-091203-a1b2c3d",
                  "commit": { "sha": "a1b2c3d", "time": "2026-11-26T09:10:44Z" },
                  "trigger": { "kind": "ai-agent", "name": "Claude Code" }, "product": "showkitchen",
                  "subProduct": { "name": "bestellen", "version": "0.5.0" },
                  "showcase": "demo", "experiment": "mvp0", "environment": "werkplek", "releaseType": "planned" }
}
```

## Voorbeeld: tussenstand en mislukte stap (`subject.content`)

```json
{ "testSuite": { "id": "sk-demo-20261126-091203-a1b2c3d-end-to-end", "name": "end-to-end" },
  "testType": "end-to-end", "tests": 7, "passed": 6, "failed": 1, "skipped": 0 }
```

```json
{ "taskName": "unit-tests", "step": 2, "title": "Unittests", "outcome": "failed",
  "pipelineRun": { "id": "sk-demo-20261126-091203-a1b2c3d" },
  "reason": "1 van 31 unittests mislukt", "excerpt": "OrderServiceTest.rejectsEmptyOrder: expected status 400 but was 500" }
```

## Voorbeeld: een gate die dichtvalt

```json
{
  "context": {
    "version": "0.4.1",
    "id": "8d1f0c2a-5b3e-4f61-9a7c-2e4b6d8f0a13",
    "source": "fcd-pipeline/0.4.0",
    "type": "dev.cdeventsx.fcd-qualitygate.evaluated.0.1.0",
    "timestamp": "2026-11-26T09:14:03Z"
  },
  "subject": {
    "id": "security-gate",
    "source": "sk-demo",
    "content": {
      "gate": "security",
      "afterSteps": "6-7",
      "outcome": "closed",
      "reason": "image showkitchen/bestellen-frontend:0.2.1: kritieke of hoge kwetsbaarheid",
      "explain": "",
      "explainDetails": []
    }
  },
  "customData": {
    "durationSeconds": 0,
    "run": "sk-demo-20261126-091203-a1b2c3d",
    "commit": { "sha": "a1b2c3d", "time": "2026-11-26T09:10:44Z" },
    "trigger": { "kind": "human", "name": "sim007" },
    "product": "showkitchen",
    "subProduct": { "name": "bestellen", "version": "0.2.1",
                    "containers": { "bestellen-frontend": "0.2.1", "bestellen-backend": "0.2.0" } },
    "showcase": "demo",
    "experiment": "mvp0",
    "environment": "werkplek",
    "releaseType": "planned"
  }
}
```

## Voorbeeld: de meting van het key result

```json
{
  "context": { "version": "0.4.1", "id": "…", "source": "bestellen-backend/0.2.0",
               "type": "dev.cdeventsx.fcd-keyresult.measured.0.1.0", "timestamp": "2026-11-26T09:20:11Z" },
  "subject": { "id": "3f2b8c1e-6a4d-4e7b-9c0a-1d2e3f4a5b6c", "source": "sk-demo",
    "content": { "keyResult": "Elke bestelling van de gast komt aan bij keuken, bar en kassa",
                 "orderId": "3f2b8c1e-6a4d-4e7b-9c0a-1d2e3f4a5b6c", "tableNumber": 12, "complete": false,
                 "receivers": { "kitchen": { "arrived": true, "receivedAt": "2026-11-26T09:20:11Z" },
                                "bar": { "arrived": false },
                                "cash-register": { "arrived": true, "receivedAt": "2026-11-26T09:20:11Z" } } } },
  "customData": { "durationSeconds": 0.2, "run": "", "trigger": { "kind": "guest", "name": "gast" },
                  "product": "showkitchen", "subProduct": { "name": "bestellen", "version": "0.2.0" },
                  "showcase": "demo", "experiment": "mvp0", "environment": "lokaal" }
}
```

## Versies

- 0.5.0: optioneel `container` bij stappen en tests (een rij per container), `changelog` bij `service.deployed`, en de nieuwe soorten `fcd-demo.reset` en `fcd-demo.merged`. Uitbreiding, niet breaking. Stuur de nieuwe soort pas naar een Dashboard dat 0.5.0 kent.
- 0.4.0: begin en einde per stap en per gate, voor de live weergave. Nieuw: `fcd-qualitygate.started` (met `waitingFor`), `testsuiterun.started`, `fcd-testsuiterun.progressed`; optioneel `excerpt` bij `taskrun.finished` en `steps` bij `pipelinerun.started`. Uitbreiding, niet breaking.
- 0.3.1: JSON Schema draft-07 (het dialect waarvoor Apicurio Registry compatibiliteit controleert) en een eigen versieveld `x-version`; de inhoud is gelijk gebleven.
- 0.3.0: showcase `fcd` (de levering van FCD zelf); elke showcase een eigen Dashboard. Het contract staat nu in fcd-dashboard (de aanbieder).
- 0.2.0: `trigger.kind` `test` voor bestellingen van end-to-end tests; die tellen niet mee voor het key result. Uitbreiding, niet breaking.
- 0.1.2: het patroon voor `experiment` is weer weg: dat was een breaking change (gevonden door de contracttest, oasdiff). Weer compatibel met 0.1.0.
- 0.1.1: `experiment` alleen letters, cijfers, `.`, `_` en `-` (gevonden door de contracttest van het Dashboard).
- 0.1.0: eerste versie.
