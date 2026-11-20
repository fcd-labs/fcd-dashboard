# fcd-dashboard

Deelproduct **Dashboard** van het product [FCD](https://fcd-labs.github.io/fcd/). Eén Dashboard voor alle showcases; elke showcase draait een eigen exemplaar met een eigen store en poort (uit de configuratie van de showcase, gestart met `fcd up`).

Laat per product en per deelproduct zien: de status van de pipelines en de quality gates, de KPI's (DORA), de testresultaten, het key result en de logregels. Met een statusrapport. Frontend (Angular), backend (Kotlin, Ktor) en store (PostgreSQL).

Meer over deze showcase: https://fcd-labs.github.io/fcd/showcases/demo/

| Container | Wat |
|---|---|
| `dashboard-frontend` | Angular, via nginx op http://127.0.0.1:8070 |
| `dashboard-backend` | Kotlin, Ktor: ontvangt logregels (`POST /api/v1/events`) en berekent de schermen |
| `dashboard-store` | PostgreSQL: alle logregels, per experiment |

Contracten: de logregel ([logline.openapi.yaml](contracts/logline.openapi.yaml), met de beschrijving in [logline.md](contracts/logline.md)) en frontend–backend ([dashboard-api.openapi.yaml](contracts/dashboard-api.openapi.yaml)).
Elk experiment (zoals `mvp0`) heeft een eigen, lege log; eerdere experimenten blijven bewaard en zijn te kiezen. Experimenten die met `test-` beginnen zijn voor de end-to-end tests.

Bouwen en testen: `../fcd-pipeline/fcd ci .` Uitrollen doet de Pipeline na een merge op `main`.

Licentie: [MIT](LICENSE).
