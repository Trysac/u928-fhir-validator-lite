package io.github.trysac.fhir_validator_lite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Property-based tests for {@link Validator} and {@link JsonParser}. */
class ValidatorPropertiesTest {

  private static final Validator VALIDATOR = createValidator();

  private static Validator createValidator() {
    try (InputStream pat =
            ValidatorPropertiesTest.class.getResourceAsStream(
                "/fixtures/patient-structure-definition.json");
        InputStream gender =
            ValidatorPropertiesTest.class.getResourceAsStream(
                "/fixtures/valueset-administrative-gender.json")) {
      return Validator.load(List.of(pat.readAllBytes()), List.of(gender.readAllBytes()));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Property
  void validPatientIdsAreAlwaysAccepted(@ForAll("validFhirIds") String id) {
    String json = "{\"resourceType\":\"Patient\",\"id\":\"" + id + "\"}";
    List<Issue> issues = VALIDATOR.validate(json.getBytes(StandardCharsets.UTF_8));
    assertEquals(0, issues.size());
  }

  @Property
  void operationOutcomeOutputIsAlwaysValidJson(@ForAll("safeDiagnosticStrings") String diag) {
    Issue issue = new Issue("error", "value", "Patient.id", diag);
    String outcome = Validator.toOperationOutcome(List.of(issue));
    assertTrue(outcome.startsWith("{\"resourceType\":\"OperationOutcome\",\"issue\":["));
    JsonParser.JsonValue parsed = JsonParser.parse(outcome.getBytes(StandardCharsets.UTF_8));
    assertTrue(parsed instanceof JsonParser.JsonObject);
  }

  @Property
  void exceptionMessagesAreCappedAtTwoHundredCharacters(@ForAll String suffix) {
    String longMessage = "x".repeat(250) + suffix;
    FhirException ex = new FhirException("invalid_json", longMessage);
    assertTrue(ex.getMessage().length() <= 200);
    assertEquals("invalid_json", ex.code());
  }

  @Property
  void leadingZeroNumbersAreAlwaysRejectedByJsonParser(@ForAll("positiveDigits") String digits) {
    String invalidJson = "{\"resourceType\":\"Patient\",\"MultipleBirthInteger\":0" + digits + "}";
    FhirException ex =
        assertThrows(
            FhirException.class,
            () -> VALIDATOR.validate(invalidJson.getBytes(StandardCharsets.UTF_8)));
    assertEquals("invalid_json", ex.code());
  }

  @Provide
  Arbitrary<String> validFhirIds() {
    return Arbitraries.strings()
        .withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.-")
        .ofMinLength(1)
        .ofMaxLength(64);
  }

  @Provide
  Arbitrary<String> safeDiagnosticStrings() {
    return Arbitraries.strings().all().ofMinLength(0).ofMaxLength(120);
  }

  @Provide
  Arbitrary<String> positiveDigits() {
    return Arbitraries.strings().numeric().ofMinLength(1).ofMaxLength(8);
  }
}
