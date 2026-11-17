package io.github.trysac.fhir_validator_lite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Acceptance and edge-case tests for {@link Validator}. */
class ValidatorTest {

  private static byte[] patientDef;
  private static byte[] observationDef;
  private static byte[] quantityDef;
  private static byte[] genderVs;
  private static byte[] obsStatusVs;
  private static Validator validator;

  @BeforeAll
  static void setUpFixtures() throws IOException {
    patientDef = readFixture("/fixtures/patient-structure-definition.json");
    observationDef = readFixture("/fixtures/observation-structure-definition.json");
    quantityDef = readFixture("/fixtures/quantity-structure-definition.json");
    genderVs = readFixture("/fixtures/valueset-administrative-gender.json");
    obsStatusVs = readFixture("/fixtures/valueset-observation-status.json");
    validator =
        Validator.load(
            List.of(patientDef, observationDef, quantityDef), List.of(genderVs, obsStatusVs));
  }

  private static byte[] readFixture(String path) throws IOException {
    try (InputStream in = ValidatorTest.class.getResourceAsStream(path)) {
      assertNotNull(in, "Missing fixture: " + path);
      return in.readAllBytes();
    }
  }

  private static byte[] utf8(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void validPatientHasZeroIssues() {
    String payload =
        "{\"resourceType\":\"Patient\",\"id\":\"example\",\"gender\":\"female\",\"birthDate\":\"1974-12-25\"}";
    List<Issue> issues = validator.validate(utf8(payload));
    assertEquals(0, issues.size());
  }

  @Test
  void invalidGenderBindingAndInvalidBirthDateGiveExpectedCodesAndExpressions() {
    String badGender = "{\"resourceType\":\"Patient\",\"gender\":\"femalex\"}";
    List<Issue> genderIssues = validator.validate(utf8(badGender));
    assertEquals(1, genderIssues.size());
    Issue gIssue = genderIssues.get(0);
    assertEquals("error", gIssue.severity());
    assertEquals("code-invalid", gIssue.code());
    assertEquals("Patient.gender", gIssue.expression());

    String badBirthDate = "{\"resourceType\":\"Patient\",\"birthDate\":\"1974-13-25\"}";
    List<Issue> dateIssues = validator.validate(utf8(badBirthDate));
    assertEquals(1, dateIssues.size());
    Issue dIssue = dateIssues.get(0);
    assertEquals("error", dIssue.severity());
    assertEquals("value", dIssue.code());
    assertEquals("Patient.birthDate", dIssue.expression());
  }

  @Test
  void observationWithoutStatusAndCodeGivesTwoRequiredIssuesOrderedByExpression() {
    String obs = "{\"resourceType\":\"Observation\",\"id\":\"obs-1\"}";
    List<Issue> issues = validator.validate(utf8(obs));
    assertEquals(2, issues.size());
    assertEquals("required", issues.get(0).code());
    assertEquals("Observation.code", issues.get(0).expression());
    assertEquals("required", issues.get(1).code());
    assertEquals("Observation.status", issues.get(1).expression());
  }

  @Test
  void unknownElementArrayForMaxOneAndChoiceTypesAreChecked() {
    String unknownFoo = "{\"resourceType\":\"Patient\",\"foo\":\"bar\"}";
    List<Issue> fooIssues = validator.validate(utf8(unknownFoo));
    assertEquals(1, fooIssues.size());
    assertEquals("structure", fooIssues.get(0).code());
    assertEquals("Patient.foo", fooIssues.get(0).expression());

    String genderArray = "{\"resourceType\":\"Patient\",\"gender\":[\"female\"]}";
    List<Issue> genderArrIssues = validator.validate(utf8(genderArray));
    assertEquals(1, genderArrIssues.size());
    assertEquals("structure", genderArrIssues.get(0).code());
    assertEquals("Patient.gender", genderArrIssues.get(0).expression());

    String validObsQuantity =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"Glucose\"},"
            + "\"valueQuantity\":{\"value\":5.4,\"unit\":\"mmol/L\"}}";
    assertEquals(0, validator.validate(utf8(validObsQuantity)).size());

    String invalidObsChoice =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"Glucose\"},"
            + "\"valueFoo\":\"bar\"}";
    List<Issue> choiceIssues = validator.validate(utf8(invalidObsChoice));
    assertEquals(1, choiceIssues.size());
    assertEquals("structure", choiceIssues.get(0).code());
    assertEquals("Observation.valueFoo", choiceIssues.get(0).expression());
  }

  @Test
  void toOperationOutcomeFormatsJsonCorrectly() {
    String badGender = "{\"resourceType\":\"Patient\",\"gender\":\"femalex\"}";
    List<Issue> issues = validator.validate(utf8(badGender));
    String outcome = Validator.toOperationOutcome(issues);
    assertTrue(outcome.startsWith("{\"resourceType\":\"OperationOutcome\",\"issue\":["));
    assertTrue(outcome.contains("\"severity\":\"error\""));
    assertTrue(outcome.contains("\"code\":\"code-invalid\""));
    assertTrue(outcome.contains("\"diagnostics\":"));
    assertTrue(outcome.contains("\"expression\":[\"Patient.gender\"]"));

    // Round-trip parse of OperationOutcome ensures valid JSON
    JsonParser.JsonValue parsed = JsonParser.parse(utf8(outcome));
    assertTrue(parsed instanceof JsonParser.JsonObject);
  }

  @Test
  void enforcesSizeLimitDepthLimitInvalidJsonAndUnknownResourceType() {
    byte[] oversized = new byte[4_194_305];
    FhirException tooLarge = assertThrows(FhirException.class, () -> validator.validate(oversized));
    assertEquals("input_too_large", tooLarge.code());

    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 65; i++) {
      deep.append("{\"a\":");
    }
    deep.append("1");
    for (int i = 0; i < 65; i++) {
      deep.append('}');
    }
    FhirException tooDeep =
        assertThrows(FhirException.class, () -> validator.validate(utf8(deep.toString())));
    assertEquals("too_deep", tooDeep.code());

    FhirException brokenJson =
        assertThrows(FhirException.class, () -> validator.validate(utf8("{\"a\":")));
    assertEquals("invalid_json", brokenJson.code());

    FhirException duplicateKeys =
        assertThrows(
            FhirException.class,
            () ->
                validator.validate(
                    utf8("{\"resourceType\":\"Patient\",\"id\":\"1\",\"id\":\"2\"}")));
    assertEquals("invalid_json", duplicateKeys.code());

    FhirException unknownType =
        assertThrows(
            FhirException.class,
            () -> validator.validate(utf8("{\"resourceType\":\"Encounter\"}")));
    assertEquals("unknown_resource_type", unknownType.code());
  }

  @Test
  void edgeCasesEmptyArrayNullValueMaxStarSingleObjectAndContainedResource() {
    // Empty array -> structure
    String emptyArray = "{\"resourceType\":\"Patient\",\"name\":[]}";
    List<Issue> emptyArrIssues = validator.validate(utf8(emptyArray));
    assertEquals(1, emptyArrIssues.size());
    assertEquals("structure", emptyArrIssues.get(0).code());
    assertEquals("Patient.name", emptyArrIssues.get(0).expression());

    // Null value -> structure
    String nullValue = "{\"resourceType\":\"Patient\",\"gender\":null}";
    List<Issue> nullIssues = validator.validate(utf8(nullValue));
    assertEquals(1, nullIssues.size());
    assertEquals("structure", nullIssues.get(0).code());
    assertEquals("Patient.gender", nullIssues.get(0).expression());

    // max '*' given as single object -> structure
    String singleObjForArray = "{\"resourceType\":\"Patient\",\"name\":{\"family\":\"Doe\"}}";
    List<Issue> maxStarIssues = validator.validate(utf8(singleObjForArray));
    assertEquals(1, maxStarIssues.size());
    assertEquals("structure", maxStarIssues.get(0).code());
    assertEquals("Patient.name", maxStarIssues.get(0).expression());

    // Contained resource validated recursively
    String withContained =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"HR\"},"
            + "\"contained\":[{\"resourceType\":\"Patient\",\"gender\":\"femalex\"}]}";
    List<Issue> containedIssues = validator.validate(utf8(withContained));
    assertEquals(1, containedIssues.size());
    assertEquals("code-invalid", containedIssues.get(0).code());
    assertEquals("Observation.contained.gender", containedIssues.get(0).expression());
  }

  @Test
  void primitiveExtensionsAndImplicitResourceFieldsAreAccepted() {
    String payload =
        "{\"resourceType\":\"Patient\",\"id\":\"pat-1\","
            + "\"meta\":{\"versionId\":\"1\"},"
            + "\"text\":{\"status\":\"generated\"},"
            + "\"extension\":[{\"url\":\"http://example.org/ext\"}],"
            + "\"modifierExtension\":[{\"url\":\"http://example.org/mod\"}],"
            + "\"birthDate\":\"1974-12-25\","
            + "\"_birthDate\":{\"id\":\"bd-1\",\"extension\":[{\"url\":\"http://example.org/time\"}]}}";
    List<Issue> issues = validator.validate(utf8(payload));
    assertEquals(0, issues.size());

    // Primitive extension as array when max is 1 -> structure
    String badPrimExt = "{\"resourceType\":\"Patient\",\"_birthDate\":[]}";
    List<Issue> badExtIssues = validator.validate(utf8(badPrimExt));
    assertEquals(1, badExtIssues.size());
    assertEquals("structure", badExtIssues.get(0).code());
    assertEquals("Patient._birthDate", badExtIssues.get(0).expression());
  }

  @Test
  void complexTypeValidationAndBackboneElements() {
    // Quantity.value is decimal; passing a string triggers 'value' at
    // Observation.valueQuantity.value
    String badQuantityValue =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"Glucose\"},"
            + "\"valueQuantity\":{\"value\":\"not-a-decimal\",\"unit\":\"mmol/L\"}}";
    List<Issue> qIssues = validator.validate(utf8(badQuantityValue));
    assertEquals(1, qIssues.size());
    assertEquals("value", qIssues.get(0).code());
    assertEquals("Observation.valueQuantity.value", qIssues.get(0).expression());

    // BackboneElement Observation.note requires text
    String missingNoteText =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"Glucose\"},"
            + "\"note\":[{}]}";
    List<Issue> noteIssues = validator.validate(utf8(missingNoteText));
    assertEquals(1, noteIssues.size());
    assertEquals("required", noteIssues.get(0).code());
    assertEquals("Observation.note.text", noteIssues.get(0).expression());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1974",
        "1974-12",
        "1974-12-25",
        "2024-02-29",
        "2024-02-29T14:30:00Z",
        "2024-02-29T14:30:00.123+02:00",
        "2024-02-29T14:30:00-05:00"
      })
  void validDateTimeValuesPass(String dt) {
    String json =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"T\"},"
            + "\"effectiveDateTime\":\""
            + dt
            + "\"}";
    assertEquals(0, validator.validate(utf8(json)).size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0000-01-01",
        "1974-13-25",
        "2023-02-29",
        "2024-04-31",
        "2024-02-29T14:30:00",
        "2024-02-29T25:00:00Z",
        "2024-02-29T14:30:00+15:00",
        "1974-12-25 extra"
      })
  void invalidDateTimeValuesFail(String dt) {
    String json =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"T\"},"
            + "\"effectiveDateTime\":\""
            + dt
            + "\"}";
    List<Issue> issues = validator.validate(utf8(json));
    assertEquals(1, issues.size());
    assertEquals("value", issues.get(0).code());
    assertEquals("Observation.effectiveDateTime", issues.get(0).expression());
  }

  @Test
  void primitiveTypesIntegerBooleanCodeIdUriInstantTime() {
    // integer out of 32-bit range
    String overflowInt = "{\"resourceType\":\"Patient\",\"MultipleBirthInteger\":2147483648}";
    List<Issue> intIssues = validator.validate(utf8(overflowInt));
    assertEquals(1, intIssues.size());
    assertEquals("value", intIssues.get(0).code());
    assertEquals("Patient.MultipleBirthInteger", intIssues.get(0).expression());

    // decimal given for integer
    String decForInt = "{\"resourceType\":\"Patient\",\"MultipleBirthInteger\":1.5}";
    assertEquals("value", validator.validate(utf8(decForInt)).get(0).code());

    // valid integer
    String okInt = "{\"resourceType\":\"Patient\",\"MultipleBirthInteger\":2}";
    assertEquals(0, validator.validate(utf8(okInt)).size());

    // id validation: > 64 chars or invalid chars
    String badId = "{\"resourceType\":\"Patient\",\"id\":\"invalid_id_with_underscore\"}";
    List<Issue> idIssues = validator.validate(utf8(badId));
    assertEquals(1, idIssues.size());
    assertEquals("value", idIssues.get(0).code());
    assertEquals("Patient.id", idIssues.get(0).expression());

    // code with leading/trailing whitespace
    String badCode =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"T\"},"
            + "\"valueQuantity\":{\"code\":\" mmol/L \"}}";
    List<Issue> codeIssues = validator.validate(utf8(badCode));
    assertEquals(1, codeIssues.size());
    assertEquals("value", codeIssues.get(0).code());
    assertEquals("Observation.valueQuantity.code", codeIssues.get(0).expression());

    // uri with whitespace
    String badUri =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"T\"},"
            + "\"valueQuantity\":{\"system\":\"http://unitsofmeasure.org/ with space\"}}";
    List<Issue> uriIssues = validator.validate(utf8(badUri));
    assertEquals(1, uriIssues.size());
    assertEquals("value", uriIssues.get(0).code());
    assertEquals("Observation.valueQuantity.system", uriIssues.get(0).expression());

    // object where primitive is expected -> structure
    String objForPrim = "{\"resourceType\":\"Patient\",\"active\":{\"val\":true}}";
    List<Issue> objIssues = validator.validate(utf8(objForPrim));
    assertEquals(1, objIssues.size());
    assertEquals("structure", objIssues.get(0).code());
    assertEquals("Patient.active", objIssues.get(0).expression());

    // valid instant and time
    String okInstantAndTime =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"T\"},"
            + "\"issued\":\"2024-01-15T10:20:30Z\",\"valueTime\":\"10:20:30\"}";
    assertEquals(0, validator.validate(utf8(okInstantAndTime)).size());
  }

  @Test
  void elementRulesAndDefinitionErrorHandling() {
    List<ElementRule> rules = validator.rulesFor("Patient");
    assertFalse(rules.isEmpty());
    assertTrue(rules.stream().anyMatch(r -> "Patient.gender".equals(r.path())));

    FhirException unknownRules =
        assertThrows(FhirException.class, () -> validator.rulesFor("NonExistent"));
    assertEquals("unknown_resource_type", unknownRules.code());

    FhirException badDef =
        assertThrows(
            FhirException.class,
            () -> Validator.load(List.of(utf8("{\"resourceType\":\"Patient\"}")), List.of()));
    assertEquals("invalid_definition", badDef.code());

    FhirException badVs =
        assertThrows(
            FhirException.class,
            () -> Validator.load(List.of(), List.of(utf8("{\"resourceType\":\"ValueSet\"}"))));
    assertEquals("invalid_definition", badVs.code());
  }

  @Test
  void additionalCoverageTests() {
    // 1. ValueSet expansion with compose.include.concept
    String vsWithCompose =
        "{\"resourceType\":\"ValueSet\",\"url\":\"http://example.org/vs/composed\",\"compose\":{\"include\":[{\"concept\":[{\"code\":\"c1\"},{\"code\":\"c2\"}]}]}}";
    Validator vCompose = Validator.load(List.of(patientDef), List.of(utf8(vsWithCompose)));
    assertNotNull(vCompose);

    // 2. ValueSet expansion with nested contains
    String vsWithNestedContains =
        "{\"resourceType\":\"ValueSet\",\"url\":\"http://example.org/vs/nested\",\"expansion\":{\"contains\":[{\"code\":\"parent\",\"contains\":[{\"code\":\"child\"}]}]}}";
    Validator vNested = Validator.load(List.of(patientDef), List.of(utf8(vsWithNestedContains)));
    assertNotNull(vNested);

    // 3. Coding and CodeableConcept required binding validation
    String sdWithBinding =
        "{\"resourceType\":\"StructureDefinition\",\"type\":\"ResourceWithBinding\",\"snapshot\":{\"element\":["
            + "{\"path\":\"ResourceWithBinding\",\"min\":0,\"max\":\"*\"},"
            + "{\"path\":\"ResourceWithBinding.codingElem\",\"min\":0,\"max\":\"1\",\"type\":[{\"code\":\"Coding\"}],\"binding\":{\"strength\":\"required\",\"valueSet\":\"http://example.org/vs/composed\"}},"
            + "{\"path\":\"ResourceWithBinding.ccElem\",\"min\":0,\"max\":\"1\",\"type\":[{\"code\":\"CodeableConcept\"}],\"binding\":{\"strength\":\"required\",\"valueSet\":\"http://example.org/vs/composed\"}}"
            + "]}}";
    Validator vBinding = Validator.load(List.of(utf8(sdWithBinding)), List.of(utf8(vsWithCompose)));

    // valid coding
    String validCoding =
        "{\"resourceType\":\"ResourceWithBinding\",\"codingElem\":{\"code\":\"c1\"}}";
    assertEquals(0, vBinding.validate(utf8(validCoding)).size());

    // invalid coding
    String invalidCoding =
        "{\"resourceType\":\"ResourceWithBinding\",\"codingElem\":{\"code\":\"c_bad\"}}";
    List<Issue> icIssues = vBinding.validate(utf8(invalidCoding));
    assertEquals(1, icIssues.size());
    assertEquals("code-invalid", icIssues.get(0).code());

    // valid CodeableConcept
    String validCC =
        "{\"resourceType\":\"ResourceWithBinding\",\"ccElem\":{\"coding\":[{\"code\":\"c2\"}]}}";
    assertEquals(0, vBinding.validate(utf8(validCC)).size());

    // invalid CodeableConcept
    String invalidCC =
        "{\"resourceType\":\"ResourceWithBinding\",\"ccElem\":{\"coding\":[{\"code\":\"c_bad\"}]}}";
    List<Issue> iccIssues = vBinding.validate(utf8(invalidCC));
    assertEquals(1, iccIssues.size());
    assertEquals("code-invalid", iccIssues.get(0).code());

    // 4. Repeatable primitive extension array
    String sdRepeatable =
        "{\"resourceType\":\"StructureDefinition\",\"type\":\"ResourceRepeatable\",\"snapshot\":{\"element\":["
            + "{\"path\":\"ResourceRepeatable\",\"min\":0,\"max\":\"*\"},"
            + "{\"path\":\"ResourceRepeatable.tags\",\"min\":0,\"max\":\"*\",\"type\":[{\"code\":\"string\"}]}"
            + "]}}";
    Validator vRepeatable = Validator.load(List.of(utf8(sdRepeatable)), List.of());
    String validRepeatExt =
        "{\"resourceType\":\"ResourceRepeatable\",\"tags\":[\"t1\"],\"_tags\":[{\"id\":\"ext1\"}]}";
    assertEquals(0, vRepeatable.validate(utf8(validRepeatExt)).size());

    String invalidRepeatExtEmpty =
        "{\"resourceType\":\"ResourceRepeatable\",\"tags\":[\"t1\"],\"_tags\":[]}";
    assertEquals(1, vRepeatable.validate(utf8(invalidRepeatExtEmpty)).size());

    String invalidRepeatExtNotObj =
        "{\"resourceType\":\"ResourceRepeatable\",\"tags\":[\"t1\"],\"_tags\":[\"not-an-obj\"]}";
    assertEquals(1, vRepeatable.validate(utf8(invalidRepeatExtNotObj)).size());

    // 5. Array max > 1 check
    String sdMaxTwo =
        "{\"resourceType\":\"StructureDefinition\",\"type\":\"ResourceMaxTwo\",\"snapshot\":{\"element\":["
            + "{\"path\":\"ResourceMaxTwo\",\"min\":0,\"max\":\"*\"},"
            + "{\"path\":\"ResourceMaxTwo.items\",\"min\":0,\"max\":\"2\",\"type\":[{\"code\":\"string\"}]}"
            + "]}}";
    Validator vMaxTwo = Validator.load(List.of(utf8(sdMaxTwo)), List.of());
    String tooManyItems = "{\"resourceType\":\"ResourceMaxTwo\",\"items\":[\"a\",\"b\",\"c\"]}";
    List<Issue> mtIssues = vMaxTwo.validate(utf8(tooManyItems));
    assertEquals(1, mtIssues.size());
    assertEquals("structure", mtIssues.get(0).code());

    // 6. Max 0 prohibition check
    String sdMaxZero =
        "{\"resourceType\":\"StructureDefinition\",\"type\":\"ResourceMaxZero\",\"snapshot\":{\"element\":["
            + "{\"path\":\"ResourceMaxZero\",\"min\":0,\"max\":\"*\"},"
            + "{\"path\":\"ResourceMaxZero.forbidden\",\"min\":0,\"max\":\"0\",\"type\":[{\"code\":\"string\"}]}"
            + "]}}";
    Validator vMaxZero = Validator.load(List.of(utf8(sdMaxZero)), List.of());
    String withForbidden = "{\"resourceType\":\"ResourceMaxZero\",\"forbidden\":\"present\"}";
    List<Issue> mzIssues = vMaxZero.validate(utf8(withForbidden));
    assertEquals(1, mzIssues.size());
    assertEquals("structure", mzIssues.get(0).code());

    // 7. Primitive types coverage: positiveInt, unsignedInt, boolean, etc.
    String sdPrimitives =
        "{\"resourceType\":\"StructureDefinition\",\"type\":\"ResourcePrim\",\"snapshot\":{\"element\":["
            + "{\"path\":\"ResourcePrim\",\"min\":0,\"max\":\"*\"},"
            + "{\"path\":\"ResourcePrim.pos\",\"min\":0,\"max\":\"1\",\"type\":[{\"code\":\"positiveInt\"}]},"
            + "{\"path\":\"ResourcePrim.uns\",\"min\":0,\"max\":\"1\",\"type\":[{\"code\":\"unsignedInt\"}]},"
            + "{\"path\":\"ResourcePrim.b64\",\"min\":0,\"max\":\"1\",\"type\":[{\"code\":\"base64Binary\"}]},"
            + "{\"path\":\"ResourcePrim.txt\",\"min\":0,\"max\":\"1\",\"type\":[{\"code\":\"string\"}]},"
            + "{\"path\":\"ResourcePrim.sysStr\",\"min\":0,\"max\":\"1\",\"type\":[{\"code\":\"http://hl7.org/fhirpath/System.String\"}]}"
            + "]}}";
    Validator vPrim = Validator.load(List.of(utf8(sdPrimitives)), List.of());

    String okPrims =
        "{\"resourceType\":\"ResourcePrim\",\"pos\":5,\"uns\":0,\"b64\":\"aGVsbG8=\",\"txt\":\"abc\",\"sysStr\":\"def\"}";
    assertEquals(0, vPrim.validate(utf8(okPrims)).size());

    String badPos = "{\"resourceType\":\"ResourcePrim\",\"pos\":0}";
    assertEquals(1, vPrim.validate(utf8(badPos)).size());

    String badUns = "{\"resourceType\":\"ResourcePrim\",\"uns\":-1}";
    assertEquals(1, vPrim.validate(utf8(badUns)).size());

    String emptyStr = "{\"resourceType\":\"ResourcePrim\",\"txt\":\"\"}";
    assertEquals(1, vPrim.validate(utf8(emptyStr)).size());

    // 8. Definition errors: invalid max, invalid min, missing path, etc.
    assertThrows(
        FhirException.class,
        () ->
            Validator.load(
                List.of(
                    utf8(
                        "{\"resourceType\":\"StructureDefinition\",\"type\":\"Bad\",\"snapshot\":{\"element\":[{\"path\":\"Bad.foo\",\"min\":\"nan\"}]}}")),
                List.of()));
    assertThrows(
        FhirException.class,
        () ->
            Validator.load(
                List.of(
                    utf8(
                        "{\"resourceType\":\"StructureDefinition\",\"type\":\"Bad\",\"snapshot\":{\"element\":[{\"path\":\"Bad.foo\",\"min\":-1}]}}")),
                List.of()));
    assertThrows(
        FhirException.class,
        () ->
            Validator.load(
                List.of(
                    utf8(
                        "{\"resourceType\":\"StructureDefinition\",\"type\":\"Bad\",\"snapshot\":{\"element\":[{\"path\":\"Bad.foo\",\"min\":5,\"max\":\"2\"}]}}")),
                List.of()));
    assertThrows(
        FhirException.class,
        () ->
            Validator.load(
                List.of(
                    utf8(
                        "{\"resourceType\":\"StructureDefinition\",\"type\":\"Bad\",\"snapshot\":{\"element\":[{\"path\":\"Bad.foo\",\"type\":\"not-array\"}]}}")),
                List.of()));
    assertThrows(
        FhirException.class,
        () ->
            Validator.load(
                List.of(
                    utf8(
                        "{\"resourceType\":\"StructureDefinition\",\"type\":\"Bad\",\"snapshot\":{\"element\":[{\"path\":\"Bad.foo\",\"type\":[{}]}]}}")),
                List.of()));
    assertThrows(
        FhirException.class,
        () ->
            Validator.load(
                List.of(
                    utf8(
                        "{\"resourceType\":\"StructureDefinition\",\"type\":\"Bad\",\"snapshot\":{\"element\":[{}]}}")),
                List.of()));
    assertThrows(
        FhirException.class,
        () ->
            Validator.load(
                List.of(
                    utf8(
                        "{\"resourceType\":\"StructureDefinition\",\"type\":\"Bad\",\"snapshot\":{\"element\":[]}}")),
                List.of()));
    assertThrows(
        FhirException.class,
        () ->
            Validator.load(
                List.of(utf8("{\"resourceType\":\"StructureDefinition\",\"snapshot\":{}}")),
                List.of()));

    // 9. JsonParser escapes and characters
    String jsonWithEscapes =
        "{\"resourceType\":\"ResourcePrim\",\"txt\":\"line\\nslash\\\\quote\\\"tab\\tformfeed\\ffoo\"}";
    assertEquals(0, vPrim.validate(utf8(jsonWithEscapes)).size());

    // 10. JsonParser unicode escapes
    String jsonWithUnicode = "{\"resourceType\":\"ResourcePrim\",\"txt\":\"\\u0041\"}";
    assertEquals(0, vPrim.validate(utf8(jsonWithUnicode)).size());

    // 11. JsonParser surrogate pair
    String jsonWithSurrogate = "{\"resourceType\":\"ResourcePrim\",\"txt\":\"\\uD83D\\uDE00\"}";
    assertEquals(0, vPrim.validate(utf8(jsonWithSurrogate)).size());

    // 12. JsonParser numbers with exponent and decimals
    String jsonExp =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"T\"},\"valueQuantity\":{\"value\":1.23e2}}";
    assertEquals(0, validator.validate(utf8(jsonExp)).size());

    // 13. Primitive extension with id and extension property
    String primExtValid =
        "{\"resourceType\":\"Patient\",\"id\":\"p1\",\"_birthDate\":{\"id\":\"extId\",\"extension\":[{\"url\":\"http://foo\"}]}}";
    assertEquals(0, validator.validate(utf8(primExtValid)).size());

    // Invalid id in primitive extension
    String primExtBadId =
        "{\"resourceType\":\"Patient\",\"id\":\"p1\",\"_birthDate\":{\"id\":\"\"}}";
    assertEquals(1, validator.validate(utf8(primExtBadId)).size());

    // Unknown property in primitive extension
    String primExtUnknownProp =
        "{\"resourceType\":\"Patient\",\"id\":\"p1\",\"_birthDate\":{\"unknown\":\"foo\"}}";
    assertEquals(1, validator.validate(utf8(primExtUnknownProp)).size());

    // 14. Non-object root
    assertThrows(FhirException.class, () -> validator.validate(utf8("[\"not an object\"]")));

    // 15. Root without valid resourceType string
    assertThrows(FhirException.class, () -> validator.validate(utf8("{\"resourceType\":123}")));
    assertThrows(FhirException.class, () -> validator.validate(utf8("{\"resourceType\":\"\"}")));

    // 16. Contained resource missing resourceType
    String badContainedRt =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"T\"},\"contained\":[{}]}";
    List<Issue> contIssues = validator.validate(utf8(badContainedRt));
    assertEquals(1, contIssues.size());
    assertEquals("structure", contIssues.get(0).code());

    // Contained resource non-object
    String badContainedNonObj =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"T\"},\"contained\":[\"not-an-obj\"]}";
    List<Issue> contNonObjIssues = validator.validate(utf8(badContainedNonObj));
    assertEquals(1, contNonObjIssues.size());
    assertEquals("structure", contNonObjIssues.get(0).code());

    // Contained resource unknown type
    String unknownContained =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"T\"},\"contained\":[{\"resourceType\":\"UnknownType\"}]}";
    assertThrows(FhirException.class, () -> validator.validate(utf8(unknownContained)));

    // 17. Null elements in array
    String arrayWithNull =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"T\"},\"note\":[null]}";
    List<Issue> nullArrIssues = validator.validate(utf8(arrayWithNull));
    assertTrue(nullArrIssues.stream().anyMatch(i -> "structure".equals(i.code())));

    // 18. Complex type given non-object
    String complexNonObj = "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":123}";
    List<Issue> compIssues = validator.validate(utf8(complexNonObj));
    assertEquals(1, compIssues.size());
    assertEquals("structure", compIssues.get(0).code());
  }
}
