# Changelog

Alle wijzigingen per versie. Formaat: [Keep a Changelog](https://keepachangelog.com/nl/1.1.0/), versies: [semantic versioning](https://semver.org/lang/nl/).

## [Unreleased]

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
