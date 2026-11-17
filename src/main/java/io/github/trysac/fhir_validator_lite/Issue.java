package io.github.trysac.fhir_validator_lite;

import java.util.Objects;

/**
 * Represents a single validation finding compatible with FHIR R4 OperationOutcome.issue.
 *
 * @param severity issue severity ({@code "error"} or {@code "warning"})
 * @param code OperationOutcome issue-type code ({@code "required"}, {@code "structure"}, {@code
 *     "value"}, or {@code "code-invalid"})
 * @param expression FHIRPath-style element path where the issue occurred
 * @param diagnostics human-readable description of the validation issue
 */
public record Issue(String severity, String code, String expression, String diagnostics) {

  /** Validates that all components of the issue are non-null. */
  public Issue {
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(diagnostics, "diagnostics");
  }
}
