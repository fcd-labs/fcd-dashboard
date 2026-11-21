# fcd-dashboard

Deelproduct **Dashboard** van het product [FCD](https://fcd-labs.github.io/fcd/). Eén Dashboard voor alle showcases; elke showcase draait een eigen exemplaar met een eigen store en poort (uit de configuratie van de showcase, gestart met `fcd up`).

Laat per product en per deelproduct zien: de status van de pipelines en de quality gates, de KPI's (DORA), de testresultaten, het key result en de logregels. Met een statusrapport, en een live weergave van de pipeline met een presentatiemodus. Frontend (Angular), backend (Kotlin, Ktor) en store (PostgreSQL).

Meer over deze showcase: https://fcd-labs.github.io/fcd/showcases/demo/

| Container | Wat |
|---|---|
| `dashboard-frontend` | Angular, via nginx op http://127.0.0.1:8070 |
| `dashboard-backend` | Kotlin, Ktor: ontvangt logregels (`POST /api/v1/events`) en berekent de schermen |
| `dashboard-store` | PostgreSQL: alle logregels, per experiment |

Contracten: de logregel ([logline.openapi.yaml](contracts/logline.openapi.yaml), met de beschrijving in [logline.md](contracts/logline.md)) en frontend–backend ([dashboard-api.openapi.yaml](contracts/dashboard-api.openapi.yaml)).
Elk experiment (zoals `mvp0`) heeft een eigen, lege log; eerdere experimenten blijven bewaard en zijn te kiezen. Experimenten die met `test-` beginnen zijn voor de end-to-end tests.

## Live weergave

Tabblad **Live**: per deelproduct de run die nu loopt, met de CI-stappen en quality gates op een rij, in de volgorde uit de configuratie van de showcase. Per stap wachtend, bezig (met lopende tijd), gelukt, mislukt of overgeslagen; per gate open of dicht met de reden; tellers bij testen; bij een mislukte stap een regel uit de uitvoer; bij de mensgate op wie de pipeline wacht; na afloop de uitkomst, de versie en een link naar de run.

- **Push:** Server-Sent Events (`GET /api/v1/live`). De browser verbindt zelf opnieuw; geen verversen.
- **Presentatiemodus:** `/?presentatie` of toets `P` op het tabblad Live (`Esc`: terug). Titel, thema, lettergrootte en volgorde uit de configuratie.
- **Configuratie:** het Dashboard leest `fcd-<showcase>/fcd/*.fcd.yaml` alleen-lezen (`FCD_CONFIG_DIR`, gemount op `/config`): de stappen en gates (`Pipeline`, `Step`, `QualityGate`) en wat de live weergave toont (`Dashboard.spec.live`):

```yaml
kind: Dashboard
spec:
  live:
    subProducts: []                   # welke deelproducten, in deze volgorde; leeg is alle
    environments: [lokaal, werkplek]  # welke runs; leeg is alle
    layout: stacked                   # of side-by-side
    keepFinishedSeconds: 600          # zo lang blijft een afgelopen run groot staan
    presentation: {title: "Showcase Demo – live", theme: dark, fontScale: 1.6, focusRunning: true}
```

Zonder configuratie werkt de live weergave ook: de volgorde komt dan uit de logregels.

### Simulator

Voorbeeld-logregels met echte tussentijden, zodat de live weergave ook zonder de echte Pipeline werkt. Generiek: stappen, gates en deelproducten komen uit de configuratie. De logregels gaan naar een eigen experiment (`sim`), dus ze tellen niet mee in de KPI's van een echt experiment.

```bash
docker run --rm --network demo-dashboard_default -v "$PWD/../fcd-demo/fcd:/config:ro" \
  fcd/dashboard-backend:0.4.0 simulate --scenario codegate-dicht --speed 2 --approver Johannes
```

Scenario's: `gelukt`, `codegate-dicht`, `securitygate-dicht`, `noodpatch`, `wacht-op-mens`, `parallel` (twee deelproducten tegelijk) en `replay --file <log.jsonl> [--run <id>]` (een echte run opnieuw afspelen; mount de map met de log). Daarna: `http://127.0.0.1:8070/?tab=live&experiment=sim`. Alle opties: `simulate --help`.

## Bouwen en testen

Bouwen en testen: `../fcd-pipeline/fcd ci .` Uitrollen doet de Pipeline na een merge op `main`.

Licentie: [MIT](LICENSE).
