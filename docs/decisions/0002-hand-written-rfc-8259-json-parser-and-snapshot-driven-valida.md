# Hand-written RFC 8259 JSON parser and snapshot-driven validation without external dependencies

Status: accepted (generated, pending human review)

## Context

Embedding the official HL7 FHIR validator introduces a large dependency tree and terminology infrastructure, while general-purpose JSON libraries often permit duplicate keys by default or parse decimals into binary floating-point types.

## Decision

Implement a strict RFC 8259 JSON parser in the Java 21 standard library that keeps numbers as BigDecimal, rejects duplicate object keys, enforces a 4 MiB input size limit and 64-level nesting limit, and drives validation directly from loaded StructureDefinition snapshots and ValueSet expansions.

## Consequences

The library has zero runtime dependencies, deterministic memory and depth bounds, exact FHIRPath-style issue expressions, and strict numeric and structural fidelity for FHIR R4 JSON payloads.
