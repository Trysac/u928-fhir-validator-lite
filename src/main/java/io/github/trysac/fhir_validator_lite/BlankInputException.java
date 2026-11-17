package io.github.trysac.fhir_validator_lite;

/** The input is empty or holds only whitespace. */
public final class BlankInputException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the message {@code input is blank}. */
  public BlankInputException() {
    super("input is blank");
  }
}
