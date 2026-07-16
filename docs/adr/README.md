# Architecture Decision Records

ADR-ized breadcrumbs (commit-style `[2026-07-XX P0 fix]` style
comments) are tracked here.  Each ADR captures the **why**
behind a design decision: context, options considered,
consequences.  Source code references ADRs by filename when
context matters.

## Index

| Date | File | Decision |
|---|---|---|
| 2026-07-10 | [2026-07-10-on-device-sensor-resolution.md](2026-07-10-on-device-sensor-resolution.md) | On-device 4096 sensor resolution + cold-start camera pre-warm (v1.0) |
| 2026-07-10 | [2026-07-10-intent-action-framework.md](2026-07-10-intent-action-framework.md) | Intent↔Action framework (Phases A→J) + 2026-07-15 action-first merge |
| 2026-07-11 | [2026-07-11-auto-ocr-on-zoom-crops.md](2026-07-11-auto-ocr-on-zoom-crops.md) | Auto-OCR on every zoom crop (Phase 2) |
| 2026-07-11 | [2026-07-11-extract-text-tool.md](2026-07-11-extract-text-tool.md) | `extract_text` tool (v1.1) |
| 2026-07-11 | [2026-07-11-c3-v3-prompt-table.md](2026-07-11-c3-v3-prompt-table.md) | C3 v3 type→action table in system prompt |
| 2026-07-12 | [2026-07-12-gt-schema-dual-read.md](2026-07-12-gt-schema-dual-read.md) | GT schema dual-read (`expected_top_intent_type`) |
| 2026-07-12 | [2026-07-12-shutter-counter-8-cycle-model.md](2026-07-12-shutter-counter-8-cycle-model.md) | Shutter counter + max-8 cycle session model |
| 2026-07-13 | [2026-07-13-pp-ocrv4-local-backend.md](2026-07-13-pp-ocrv4-local-backend.md) | PP-OCRv4 local OCR backend swap |
| 2026-07-14 | [2026-07-14-v3-inversion.md](2026-07-14-v3-inversion.md) | v3.0 inversion: LLM authoritative, IntentVerifier retired |
| 2026-07-15 | [2026-07-15-v4-action-first-composite.md](2026-07-15-v4-action-first-composite.md) | v4 action-first composite + UI accent |
| 2026-07-16 | [2026-07-16-producer-consumer-pipeline.md](2026-07-16-producer-consumer-pipeline.md) | CycleManager producer/consumer pipeline + busy derived flow |

## ADR template

```markdown
# YYYY-MM-DD — <decision title>

## Status
Accepted / Superseded / Rejected.  Shipped in <commit hash(es)>.

## Context
<what problem / constraint drove the decision>

## Decision
<what we chose>

## Alternatives considered
- **A. <name>** — <description>. **Rejected**: <why>.

## Consequences
<net win + trade-offs>

## Migration
<how callers / configs / docs update, if any>

## Related decisions
<links to other ADRs in this folder>
```

ADRs are **immutable** once shipped.  Reversing a decision
ships a new ADR that supersedes the old one (link to it).