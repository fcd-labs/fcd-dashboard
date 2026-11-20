# Changelog

Alle wijzigingen per versie. Formaat: [Keep a Changelog](https://keepachangelog.com/nl/1.1.0/), versies: [semantic versioning](https://semver.org/lang/nl/).

## [Unreleased]

## [0.1.0] - 2026-11-20

### Toegevoegd
- Ontvangt logregels volgens het contract (CDEvents, `POST /api/v1/events`), gecontroleerd tegen het schema, idempotent.
- Store in PostgreSQL; per experiment een eigen log.
- Schermen: per product en deelproduct de status van de pipelines en quality gates (met reden), waar een deelproduct vastzit, de uitgerolde versie met containerversies, releases en goedgekeurde afwijkingen; testresultaten per soort en opgeteld; de logregels.
- Eigen store-image zonder `gosu` (kwetsbaarheden in de Go-standaardbibliotheek; de store draait nooit als root).
