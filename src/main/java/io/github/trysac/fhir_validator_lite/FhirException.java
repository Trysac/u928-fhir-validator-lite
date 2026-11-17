package io.github.trysac.fhir_validator_lite;

import java.util.Objects;

/**
 * Unchecked exception thrown when input exceeds limits, JSON is malformed, a definition is invalid,
 * or a resource type is unknown.
 */
public final class FhirException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  private final String code;

  /**
   * Creates a new {@code FhirException} with a machine-readable error code and message.
   *
   * @param code stable error code ({@code input_too_large}, {@code too_deep}, {@code invalid_json},
   *     {@code unknown_resource_type}, or {@code invalid_definition})
   * @param message descriptive message without sensitive payload fragments
   */
  public FhirException(String code, String message) {
    super(truncateMessage(Objects.requireNonNull(message, "message")));
    this.code = Objects.requireNonNull(code, "code");
  }

  /**
   * Returns the machine-readable error code.
   *
   * @return the error code
   */
  public String code() {
    return code;
  }

  private static String truncateMessage(String msg) {
    if (msg.length() <= 200) {
      return msg;
    }
    return msg.substring(0, 197) + "...";
  }
}
