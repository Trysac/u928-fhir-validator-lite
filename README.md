# fhir-validator-lite

FHIR R4 JSON resource validator driven by StructureDefinition snapshots that checks cardinality, datatypes, choice types and required bindings and emits OperationOutcome.

## Problem

Integrations that exchange FHIR resources need to reject malformed payloads before storing them. The official HL7 validator is a large Java application with terminology services that is heavy to embed in a service or test suite, while hand-written checks drift from the specification. This library validates FHIR R4 JSON against `StructureDefinition` snapshots given as data, with exact element paths and `OperationOutcome` output.

## Installation

Add to your `pom.xml`:

```xml
<dependency>
  <groupId>io.github.trysac</groupId>
  <artifactId>fhir-validator-lite</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Usage

```java
import io.github.trysac.fhir_validator_lite.*;
import java.nio.charset.StandardCharsets;

Validator validator = Validator.load(List.of(patientDefBytes), List.of(genderValueSetBytes));
byte[] resource = "{\"resourceType\":\"Patient\",\"id\":\"example\",\"gender\":\"female\"}".getBytes(StandardCharsets.UTF_8);
List<Issue> issues = validator.validate(resource);
String outcomeJson = Validator.toOperationOutcome(issues);
```

## API Summary

- `Validator.load(List<byte[]> structureDefinitions, List<byte[]> valueSetExpansions)`: Initializes the validator with definitions.
- `Validator.validate(byte[] resourceJson)`: Returns `List<Issue>` with validation findings.
- `Validator.toOperationOutcome(List<Issue> issues)`: Serializes issues to a standard FHIR `OperationOutcome` JSON.
- `Issue(String severity, String code, String expression, String diagnostics)`: Immutable validation finding.
- **Limits**: Rejects inputs > 4 MiB; recursion depth limit 64; strictly enforces RFC 8259 (no duplicate keys, malformed UTF-8, or invalid numbers).
- **Errors**: `FhirException` (code: `input_too_large`, `too_deep`, `invalid_json`, `unknown_resource_type`, `invalid_definition`).

## Why not the official HL7 validator?

This library provides an embeddable, dependency-free subset of the official validator. It does not require a terminology server, heavy classpath dependencies, or local R5 support, making it suitable for lightweight microservices.

## Security

See [docs/SECURITY.md](docs/SECURITY.md) for the threat model and limits.