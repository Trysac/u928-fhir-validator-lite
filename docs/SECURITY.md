# Security

## Threat Model

- **Denial of Service (DoS)**: Defended by enforcing a strict 4 MiB input byte limit (`input_too_large` error) and a maximum JSON nesting depth of 64 (`too_deep` error) to prevent stack overflow.
- **JSON Parser Injection/Desync**: Defended by a custom, strict RFC 8259 parser that rejects duplicate object keys and malformed UTF-8.
- **Data Leakage**: Defended by truncating all `FhirException` messages to 200 characters; no full resource payloads are echoed in error logs.
- **Supply Chain Attacks**: Defended by having zero runtime dependencies, relying only on the Java 21 standard library.

## Limits

- The parser rejects inputs exceeding 4,194,304 bytes.
- Parsing and validation depth is capped at 64 levels.