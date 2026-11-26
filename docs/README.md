# Documentation

| Document | What it holds |
|---|---|
| [architecture.md](architecture.md) | How the SDK is built: the native engine, the C API, the Kotlin API, lifecycle, memory, the hub and background downloads |
| [decisions.md](decisions.md) | Every design decision (`D<n>`): what was chosen, why, and which findings back it |
| [research/findings.md](research/findings.md) | The findings register (`F<n>`): every measurement and upstream reading, with its evidence |
| [research/history.md](research/history.md) | How the SDK got here, phase by phase, and what is still open |
| [release-notes.md](release-notes.md) | What changed for apps in each version: breaking changes, new API, speed-ups and fixes |
| [testing.md](testing.md) | The verification gates, what each proves, what it needs, and the performance budgets |
| [publishing.md](publishing.md) | How a version of the SDK is staged, checked and released on Maven Central, and the one-time setup |
| [design/demo-app.md](design/demo-app.md) | The demo app's design reference: experience, naming, limits, design system |
| [design/models-page.md](design/models-page.md) | The Models page: one offline-first page for suggested, downloaded and searched models (D130) |
| [design/models-search.md](design/models-search.md) | Searching Hugging Face from the Models page: paging (D127) and filters (D128) |
| [design/models-audit.md](design/models-audit.md) | The critic's audit of the Models screen; its findings (S, M, R IDs) are cited from code and marked fixed or ruled out |
| [design/models-screen.md](design/models-screen.md) | The earlier merged Models screen (D116), superseded by models-page.md; kept for the fit symbol and card rationale |
| [design/hands-free-tuning.md](design/hands-free-tuning.md) | Hands-free voice mode (D147): what to try on the phone, what to listen for, which constants to change |
| [design/server.md](design/server.md) | Built, phone run pending: serving a model to the Wi-Fi from a docked phone, `llamabro-server` and the demo's server mode |
| [design/rag.md](design/rag.md) | Approved: `llamabro-rag`, search over the person's own documents, and the demo's library |
| [../research/README.md](../research/README.md) | The experiments behind the findings: scripts, method and raw results |

## Conventions

Documents cite each other by stable ID instead of repeating content.

| ID | Meaning | Defined in |
|---|---|---|
| `D<n>` | A decision | [decisions.md](decisions.md) |
| `F<n>` | A finding, with its evidence | [research/findings.md](research/findings.md) |
| `P<n>`, `PA`, `PF`, `PD` | A development phase | [research/history.md](research/history.md) |

IDs are never reused or renumbered. A finding or decision that turned out wrong is marked **Superseded by …** and kept.

| You have… | Write it in |
|---|---|
| A measurement, or something read in upstream source | [research/findings.md](research/findings.md), as a new `F<n>` with evidence; raw data goes in `research/<phase>/<topic>/` |
| A design choice | [decisions.md](decisions.md), as a new `D<n>` citing the findings behind it |
| A change in how the SDK is built | [architecture.md](architecture.md) |
| A new check or budget | [testing.md](testing.md) |
