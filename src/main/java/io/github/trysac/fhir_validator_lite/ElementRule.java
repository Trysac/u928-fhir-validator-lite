package io.github.trysac.fhir_validator_lite;

import java.util.List;
import java.util.Objects;

/**
 * Validation rule extracted from an element entry in {@code StructureDefinition.snapshot.element}.
 *
 * @param path element path (e.g., {@code "Patient.gender"} or {@code "Observation.value[x]"})
 * @param min minimum cardinality
 * @param max maximum cardinality ({@code "*"} or a non-negative integer string)
 * @param types list of allowed FHIR datatype codes
 * @param bindingStrength binding strength (e.g., {@code "required"}, {@code "extensible"}, or
 *     {@code null} when unbound)
 * @param valueSetUrl canonical URL of the bound ValueSet (without version suffix, or {@code null})
 */
public record ElementRule(
    String path,
    int min,
    String max,
    List<String> types,
    String bindingStrength,
    String valueSetUrl) {

  /** Validates required components and makes an unmodifiable copy of {@code types}. */
  public ElementRule {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(max, "max");
    types = List.copyOf(Objects.requireNonNull(types, "types"));
  }
}
