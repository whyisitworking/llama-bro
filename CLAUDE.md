# Llama-Bro

Start every session at [docs/README.md](docs/README.md). It links each area's index, and the index gives the current
status, the phase plans and the working rules.

- Current work: the SDK v2, in [docs/sdk-v2/README.md](docs/sdk-v2/README.md).
- Decisions are `D<n>` in the design doc, findings are `F<n>` in the findings register, and documents cite each other by these IDs. Follow the conventions in [docs/README.md](docs/README.md#how-these-docs-work) when you write anything down.
- Record every new finding with its evidence. Before ending a session, update the area index's status.
- Verify every change under `sdk/` with `sdk/scripts/check.sh` (add `--device` for native, JNI or packaging changes) before committing.
