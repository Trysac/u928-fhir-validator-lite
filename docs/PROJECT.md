# Project record

## Objective

Validate FHIR R4 JSON resource payloads against `StructureDefinition` snapshots and `ValueSet` expansions using only the Java standard library.

## Scope

- Parsing and validation of FHIR R4 JSON resources.
- Cardinality, datatype, choice element, required binding, and recursive complex type checking.
- Generation of `OperationOutcome` error reports.

## Out of Scope

- FHIRPath invariants.
- Slicing and discriminators.
- Profile definition beyond base snapshots.
- Terminology servers or code system lookup.
- XML or RDF formats.
- FHIR R5.

## Effort metrics

| Metric | Value |
|---|---|
| Tokens (input / output) | 665404 / 31549 |
| Hours (human) | pending measurement |
| Cost (USD) | 0.6800 |

## Factory run u928 (measured from API usage)

| Date (UTC) | Phase | Model | Batch | Input | Output | Cache write | Cache read | Cost (USD) |
|---|---|---|---|---|---|---|---|---|
| 2026-11-17 | generation | google/gemini-4-argon | yes | 7343 | 25282 | 0 | 0 | 0.5350 |
| 2026-11-17 | fix-1 | google/gemini-3.8-flash | no | 106857 | 4531 | 0 | 529079 | 0.1368 |
| 2026-11-17 | docs | google/gemini-3.1-flash-lite | no | 22125 | 1736 | 0 | 0 | 0.0081 |

Total: $0.6800. Prices: vertex line, as measured by its adapter. Providers serve the current version of each model id, so the date tells which version answered; versions, launch stages and price sources per line are in the factory's model register (docs/MODELS.md).
