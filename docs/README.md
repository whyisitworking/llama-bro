# Documentation

| Document | What it holds |
|---|---|
| [architecture.md](architecture.md) | How the SDK is built: the native engine, the C API, the Kotlin API, lifecycle, memory, the hub and background downloads |
| [decisions.md](decisions.md) | Every design decision (`D<n>`): what was chosen, why, and which findings back it |
| [research/findings.md](research/findings.md) | The findings register (`F<n>`): every measurement and upstream reading, with its evidence |
| [research/history.md](research/history.md) | How the SDK got here, phase by phase, and what is still open |
| [release-notes.md](release-notes.md) | What changed for apps in each phase: breaking changes, new API, speed-ups and fixes |
| [testing.md](testing.md) | The verification gates, what each proves, what it needs, and the performance budgets |
| [publishing.md](publishing.md) | How a version of the SDK is staged, checked and released on Maven Central, and the one-time setup |
| [design/demo-app.md](design/demo-app.md) | The demo app's design reference: experience, naming, limits, design system |
| [design/mockups/](design/mockups/README.md) | The HTML mockups the demo app was built from |
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
