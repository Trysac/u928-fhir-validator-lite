# Testing

## Unit & Acceptance Tests

- `ValidatorTest`: Covers acceptance scenarios including `Patient`, `Observation`, and `Quantity` definitions, required binding checks (`administrative-gender`, `observation-status`), and complex type validation.
- `TextTest`: Tests basic string normalization and blank input handling for the utility class.

## Property-Based Tests

- `ValidatorPropertiesTest`: Uses jqwik to generate arbitrary FHIR IDs, diagnostic strings, and numeric values to verify `OperationOutcome` serialization, `FhirException` message truncation, and JSON parser grammar invariants (e.g., rejecting leading zeros).
- `TextPropertiesTest`: Verifies normalization idempotency and whitespace trimming invariants.