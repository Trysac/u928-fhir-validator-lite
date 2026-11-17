# Architecture

`fhir-validator-lite` operates as an immutable, thread-safe validator engine.

1. `JsonParser`: Performs strict, single-pass RFC 8259 decoding. It converts numbers to `BigDecimal` for precision and enforces global limits on size and nesting.
2. `Validator`: The core engine. It parses `StructureDefinition` data into `ElementRule` objects and indexes `ValueSet` expansions. It performs recursive validation of the JSON tree against these rules.
3. `PrimitiveValidators`: Implements the R4 section 2.24 datatype specification using `java.time` and Regex for date/time/instant validation.
4. `Issue`/`ElementRule`: Data transfer objects representing the validator's contract with the user.