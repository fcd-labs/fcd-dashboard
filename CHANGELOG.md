# Changelog

Alle wijzigingen per versie. Formaat: [Keep a Changelog](https://keepachangelog.com/nl/1.1.0/), versies: [semantic versioning](https://semver.org/lang/nl/).

## [Unreleased]

## [0.4.0] - 2026-11-21

### Toegevoegd
- Live weergave van de pipeline (tabblad Live): per deelproduct de lopende run met de CI-stappen en quality gates op een rij, in de volgorde uit de configuratie van de showcase; per stap de status met lopende tijd, per gate open of dicht met de reden, meelopende testtellers, een regel uit de uitvoer bij een mislukte stap, de wachtende mensgate, en na afloop de uitkomst, de versie en een link naar de run.
- Presentatiemodus voor een groot scherm (`?presentatie` of toets `P`), ingesteld vanuit de configuratie.
- Server-Sent Events: `GET /api/v1/live` duwt elke nieuwe logregel direct naar de browser; `GET /api/v1/live/state` geeft de stand als JSON.
- Het Dashboard leest de configuratie van de showcase alleen-lezen (`FCD_CONFIG_DIR`): stappen, gates en `Dashboard.spec.live`.
- Simulator (`java -jar app.jar simulate`): voorbeeld-logregels met echte tussentijden, scenario's voor een geslaagde run, een dichte codegate of securitygate, een noodpatch, een wachtende mensgate, twee deelproducten tegelijk en het opnieuw afspelen van een echte run.

### Gewijzigd
- Contract van de logregel 0.4.0: begin en einde per stap en per gate. Nieuw: `fcd-qualitygate.started` (met `waitingFor`), `testsuiterun.started`, `fcd-testsuiterun.progressed`; optioneel `excerpt` bij `taskrun.finished` en `steps` bij `pipelinerun.started`. Achterwaarts compatibel.
- Contract frontend–backend 0.4.0: de live weergave.
- Een link `?subproduct=<product>/<naam>` opent dat deelproduct in het overzicht.

## [0.3.1] - 2026-11-21

### Gewijzigd
- Contract van de logregel 0.3.1: JSON Schema draft-07 (het dialect waarvoor Apicurio Registry compatibiliteit controleert), met een eigen versieveld `x-version`. De inhoud is gelijk; de backend valideert met draft-07.

## [0.3.0] - 2026-11-20

### Gewijzigd
- Deelproduct van het product FCD (was fcd-demo), voor alle showcases. Repository `fcd-dashboard`.
- Geen showcase meer in de code: de showcase komt uit de omgeving (`SHOWCASE`); titel, overzicht en statusrapport noemen hem.
- Een Dashboard weigert logregels van een andere showcase (eigen store per showcase).
- Het contract van de logregel staat nu hier (0.3.0: showcase `fcd` voor de levering van FCD zelf).
- Poort uit de configuratie van de showcase.

### Toegevoegd
- `GET /api/v1/events?experiment=…`: de log van een experiment (NDJSON), voor de showcase-repository.

### Opgelost
- "Vast bij": zonder dichte gate de laatste mislukte stap, niet een eerdere die door een afwijking was afgedekt.

## [0.2.1] - 2026-11-20

### Opgelost
- Key result: bestellingen van end-to-end tests telden mee; nu tellen alleen echte gasten (contract van de logregel 0.2.0, `trigger.kind` `test`).
- Statusrapport: een deelproduct zonder containers (de Pipeline) heet "n.v.t." in plaats van "nog niet uitgerold".
- De versheidscontrole voor Maven zag niets (door `-q`); nu met een reden per onderdeel. Gevonden: json-schema-validator een major-versie achter; afwijking EXP-002 tot 2026-12-31 (goedgekeurd door Johannes). EXP-001 geldt alleen nog voor de frontend.

## [0.2.0] - 2026-11-20

### Toegevoegd
- KPI's van FCD: de DORA-metrics per product en deelproduct (doorlooptijd van commit tot uitrol, uitrolfrequentie, percentage mislukte wijzigingen, hersteltijd), hoe vaak elke quality gate dichtvalt en waarom, het verloop per dag, de runduur van de pipeline en het aantal lopende afwijkingen.
- Statusrapport in Markdown: de stand op één moment (datum, showcase, experiment, versies, pipelines en gates, KPI's, testresultaten, key result), te bekijken in het Dashboard, te bewaren als `.md` of af te drukken als PDF.
- Het key result van de intent Bestellen: hoeveel bestellingen, hoeveel volledig aangekomen bij keuken, bar en kassa, en welke niet (en waar niet).

## [0.1.0] - 2026-11-20

### Toegevoegd
- Ontvangt logregels volgens het contract (CDEvents, `POST /api/v1/events`), gecontroleerd tegen het schema, idempotent.
- Store in PostgreSQL; per experiment een eigen log.
- Schermen: per product en deelproduct de status van de pipelines en quality gates (met reden), waar een deelproduct vastzit, de uitgerolde versie met containerversies, releases en goedgekeurde afwijkingen; testresultaten per soort en opgeteld; de logregels.
- Eigen store-image zonder `gosu` (kwetsbaarheden in de Go-standaardbibliotheek; de store draait nooit als root).
