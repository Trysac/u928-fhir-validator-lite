# fhir-validator-lite

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![CI](https://github.com/Trysac/u928-fhir-validator-lite/actions/workflows/ci.yml/badge.svg)](https://github.com/Trysac/u928-fhir-validator-lite/actions/workflows/ci.yml)

FHIR R4 JSON resource validator driven by StructureDefinition snapshots that checks cardinality, datatypes, choice types and required bindings and emits OperationOutcome

## Problem

Describe in two or three sentences the concrete problem this utility solves, who hits it, and what
they do today without it. Replace this paragraph when the first real feature lands.

## Install

Maven coordinates `io.github.trysac:fhir-validator-lite`. Until it is published to Maven Central, build
it from a release tag with `mvn install`, then declare:

```xml
<dependency>
  <groupId>io.github.trysac</groupId>
  <artifactId>fhir-validator-lite</artifactId>
  <version>0.1.0</version>
</dependency>
```

Requires Java 21 or newer. No dependencies outside the standard library.

## Usage

```java
import io.github.trysac.fhir_validator_lite.Text;

// normalize trims surrounding whitespace and lower-cases the text.
String name = Text.normalize("  Hello World "); // "hello world"

// Blank input is rejected explicitly instead of returning an empty string.
Text.normalize("   "); // throws BlankInputException ("input is blank")
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md) and [decisions](docs/decisions/)
- [Testing](docs/TESTING.md) and [security](docs/SECURITY.md)
- [Project record](docs/PROJECT.md) and [stack](docs/STACK.md)
- [Changelog](CHANGELOG.md) and [contributing guide](CONTRIBUTING.md)

## License

[MIT](LICENSE) (c) 2026 Miguel Gomez
