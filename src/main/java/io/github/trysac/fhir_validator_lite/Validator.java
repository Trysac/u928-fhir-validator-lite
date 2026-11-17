package io.github.trysac.fhir_validator_lite;

import io.github.trysac.fhir_validator_lite.JsonParser.JsonArray;
import io.github.trysac.fhir_validator_lite.JsonParser.JsonNull;
import io.github.trysac.fhir_validator_lite.JsonParser.JsonObject;
import io.github.trysac.fhir_validator_lite.JsonParser.JsonString;
import io.github.trysac.fhir_validator_lite.JsonParser.JsonValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-safe, immutable FHIR R4 JSON resource validator driven by StructureDefinition snapshots
 * and ValueSet expansions.
 */
public final class Validator {

  private static final int MAX_CONTAINED_DEPTH = 64;

  private static final Set<String> IMPLICIT_RESOURCE_ELEMENTS =
      Set.of("resourceType", "id", "meta", "text", "extension", "modifierExtension", "contained");

  private static final Map<String, ElementRule> IMPLICIT_RESOURCE_RULES = createImplicitRules();

  private final Map<String, StructureDef> definitions;
  private final Map<String, Set<String>> valueSets;

  private record StructureDef(String type, List<ElementRule> rules) {}

  private Validator(Map<String, StructureDef> definitions, Map<String, Set<String>> valueSets) {
    this.definitions = definitions;
    this.valueSets = valueSets;
  }

  private static Map<String, ElementRule> createImplicitRules() {
    Map<String, ElementRule> map = new LinkedHashMap<>();
    map.put("id", new ElementRule("Resource.id", 0, "1", List.of("id"), null, null));
    map.put("meta", new ElementRule("Resource.meta", 0, "1", List.of("Meta"), null, null));
    map.put(
        "text", new ElementRule("DomainResource.text", 0, "1", List.of("Narrative"), null, null));
    map.put(
        "extension",
        new ElementRule("DomainResource.extension", 0, "*", List.of("Extension"), null, null));
    map.put(
        "modifierExtension",
        new ElementRule(
            "DomainResource.modifierExtension", 0, "*", List.of("Extension"), null, null));
    map.put(
        "contained",
        new ElementRule("DomainResource.contained", 0, "*", List.of("Resource"), null, null));
    return Map.copyOf(map);
  }

  /**
   * Loads StructureDefinition snapshots and ValueSet expansions into an immutable {@link
   * Validator}.
   *
   * @param structureDefinitions UTF-8 encoded FHIR StructureDefinition JSON payloads
   * @param valueSetExpansions UTF-8 encoded FHIR ValueSet JSON payloads
   * @return a configured {@link Validator}
   * @throws FhirException if any JSON payload is invalid or missing required definition fields
   */
  public static Validator load(List<byte[]> structureDefinitions, List<byte[]> valueSetExpansions) {
    Objects.requireNonNull(structureDefinitions, "structureDefinitions");
    Objects.requireNonNull(valueSetExpansions, "valueSetExpansions");

    Map<String, StructureDef> defs = new LinkedHashMap<>();
    for (byte[] raw : structureDefinitions) {
      Objects.requireNonNull(raw, "structureDefinition entry");
      StructureDef sd = parseStructureDefinition(raw);
      defs.put(sd.type(), sd);
    }

    Map<String, Set<String>> vsMap = new LinkedHashMap<>();
    for (byte[] raw : valueSetExpansions) {
      Objects.requireNonNull(raw, "valueSetExpansion entry");
      parseValueSetExpansion(raw, vsMap);
    }

    return new Validator(Map.copyOf(defs), Map.copyOf(vsMap));
  }

  /**
   * Returns the parsed {@link ElementRule} list for a loaded type or resource name.
   *
   * @param typeName the FHIR resource or complex type name (e.g., {@code "Patient"})
   * @return unmodifiable list of element rules
   * @throws FhirException with code {@code unknown_resource_type} if the type is not loaded
   */
  public List<ElementRule> rulesFor(String typeName) {
    Objects.requireNonNull(typeName, "typeName");
    StructureDef def = definitions.get(typeName);
    if (def == null) {
      throw new FhirException(
          "unknown_resource_type", "Unknown resource or type definition: " + typeName);
    }
    return def.rules();
  }

  /**
   * Validates a UTF-8 encoded FHIR R4 resource JSON payload.
   *
   * @param resourceJson the raw JSON bytes of the resource
   * @return list of validation issues ordered deterministically
   * @throws FhirException if the input is too large, nested too deeply, invalid JSON, or has an
   *     unknown {@code resourceType}
   */
  public List<Issue> validate(byte[] resourceJson) {
    Objects.requireNonNull(resourceJson, "resourceJson");
    JsonValue parsed = JsonParser.parse(resourceJson);
    if (!(parsed instanceof JsonObject rootObj)) {
      throw new FhirException("invalid_json", "Root of a FHIR resource must be a JSON object");
    }
    JsonValue rtVal = rootObj.members().get("resourceType");
    if (!(rtVal instanceof JsonString rtStr) || rtStr.value().isEmpty()) {
      throw new FhirException(
          "unknown_resource_type", "Missing or non-string resourceType on root object");
    }
    String resourceType = rtStr.value();
    StructureDef def = definitions.get(resourceType);
    if (def == null) {
      throw new FhirException("unknown_resource_type", "No definition loaded for " + resourceType);
    }

    List<Issue> issues = new ArrayList<>();
    validateObject(rootObj, def.type(), resourceType, def.rules(), true, 1, issues);
    return List.copyOf(issues);
  }

  /**
   * Serializes a list of {@link Issue} objects as a FHIR R4 {@code OperationOutcome} JSON string.
   *
   * @param issues the validation issues to include
   * @return compact FHIR R4 OperationOutcome JSON string
   */
  public static String toOperationOutcome(List<Issue> issues) {
    Objects.requireNonNull(issues, "issues");
    StringBuilder sb = new StringBuilder();
    sb.append("{\"resourceType\":\"OperationOutcome\",\"issue\":[");
    for (int i = 0; i < issues.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      Issue issue = Objects.requireNonNull(issues.get(i), "issue element");
      sb.append("{\"severity\":")
          .append(JsonParser.escapeJsonString(issue.severity()))
          .append(",\"code\":")
          .append(JsonParser.escapeJsonString(issue.code()))
          .append(",\"diagnostics\":")
          .append(JsonParser.escapeJsonString(issue.diagnostics()))
          .append(",\"expression\":[")
          .append(JsonParser.escapeJsonString(issue.expression()))
          .append("]}");
    }
    sb.append("]}");
    return sb.toString();
  }

  private void validateObject(
      JsonObject obj,
      String defRootPath,
      String currentExpression,
      List<ElementRule> rules,
      boolean isResource,
      int depth,
      List<Issue> issues) {
    if (depth > MAX_CONTAINED_DEPTH) {
      throw new FhirException("too_deep", "Validation nesting depth exceeds limit");
    }

    List<ElementRule> directRules = directChildRules(defRootPath, rules);

    // 1. Check required cardinality (min > 0), ordered by expression
    List<Issue> requiredIssues = new ArrayList<>();
    for (ElementRule rule : directRules) {
      if (rule.min() > 0) {
        String childSpec = rule.path().substring(defRootPath.length() + 1);
        int count = countMatchingElements(obj, childSpec, rule);
        if (count < rule.min()) {
          String expr = currentExpression + "." + childSpec;
          requiredIssues.add(
              new Issue(
                  "error",
                  "required",
                  expr,
                  "Missing required element " + expr + " (min " + rule.min() + ")"));
        }
      }
    }
    requiredIssues.sort(Comparator.comparing(Issue::expression));
    issues.addAll(requiredIssues);

    // 2. Check each present property on the JSON object
    for (Map.Entry<String, JsonValue> entry : obj.members().entrySet()) {
      String propName = entry.getKey();
      JsonValue propValue = entry.getValue();
      String propExpr = currentExpression + "." + propName;

      if (isResource && "resourceType".equals(propName)) {
        continue;
      }

      if (propValue instanceof JsonNull) {
        issues.add(
            new Issue("error", "structure", propExpr, "Null values are not allowed in FHIR JSON"));
        continue;
      }

      if (propName.startsWith("_")) {
        validatePrimitiveExtension(
            propName, propValue, propExpr, directRules, isResource, depth, issues);
        continue;
      }

      ResolvedProperty resolved = resolveProperty(propName, defRootPath, directRules, isResource);
      if (resolved == null) {
        issues.add(new Issue("error", "structure", propExpr, "Unknown element '" + propName + "'"));
        continue;
      }

      validatePropertyValue(
          propValue, resolved.rule(), resolved.chosenType(), propExpr, rules, depth, issues);
    }
  }

  private void validatePrimitiveExtension(
      String propName,
      JsonValue propValue,
      String propExpr,
      List<ElementRule> directRules,
      boolean isResource,
      int depth,
      List<Issue> issues) {
    String baseProp = propName.substring(1);
    if (baseProp.isEmpty()) {
      issues.add(
          new Issue(
              "error", "structure", propExpr, "Invalid primitive extension '" + propName + "'"));
      return;
    }
    ResolvedProperty baseResolved = resolveProperty(baseProp, "", directRules, isResource);
    if (baseResolved == null || !PrimitiveValidators.isPrimitiveType(baseResolved.chosenType())) {
      issues.add(
          new Issue(
              "error", "structure", propExpr, "Unknown primitive extension '" + propName + "'"));
      return;
    }
    boolean repeatable = isRepeatable(baseResolved.rule().max());
    if (repeatable) {
      if (!(propValue instanceof JsonArray arr) || arr.elements().isEmpty()) {
        issues.add(
            new Issue(
                "error",
                "structure",
                propExpr,
                "Primitive extension for repeatable element must be a non-empty array"));
        return;
      }
      for (JsonValue item : arr.elements()) {
        if (!(item instanceof JsonNull) && !(item instanceof JsonObject)) {
          issues.add(
              new Issue(
                  "error",
                  "structure",
                  propExpr,
                  "Primitive extension array item must be an object or null"));
        } else if (item instanceof JsonObject extObj) {
          validatePrimitiveExtensionObject(extObj, propExpr, depth, issues);
        }
      }
    } else {
      if (!(propValue instanceof JsonObject extObj)) {
        issues.add(
            new Issue(
                "error",
                "structure",
                propExpr,
                "Primitive extension '" + propName + "' must be a JSON object"));
        return;
      }
      validatePrimitiveExtensionObject(extObj, propExpr, depth, issues);
    }
  }

  private void validatePrimitiveExtensionObject(
      JsonObject extObj, String propExpr, int depth, List<Issue> issues) {
    for (Map.Entry<String, JsonValue> entry : extObj.members().entrySet()) {
      String k = entry.getKey();
      JsonValue v = entry.getValue();
      String childExpr = propExpr + "." + k;
      if ("id".equals(k)) {
        if (!PrimitiveValidators.isValidPrimitive("string", v)) {
          issues.add(new Issue("error", "value", childExpr, "Invalid id in primitive extension"));
        }
      } else if ("extension".equals(k)) {
        ElementRule extRule =
            new ElementRule("Element.extension", 0, "*", List.of("Extension"), null, null);
        validatePropertyValue(v, extRule, "Extension", childExpr, List.of(), depth + 1, issues);
      } else {
        issues.add(
            new Issue(
                "error", "structure", childExpr, "Unknown property in primitive extension: " + k));
      }
    }
  }

  private void validatePropertyValue(
      JsonValue propValue,
      ElementRule rule,
      String chosenType,
      String propExpr,
      List<ElementRule> allParentRules,
      int depth,
      List<Issue> issues) {
    boolean repeatable = isRepeatable(rule.max());
    if (repeatable) {
      if (!(propValue instanceof JsonArray arr)) {
        issues.add(
            new Issue(
                "error",
                "structure",
                propExpr,
                "Element " + propExpr + " with max '" + rule.max() + "' must be a JSON array"));
        return;
      }
      if (arr.elements().isEmpty()) {
        issues.add(
            new Issue("error", "structure", propExpr, "Empty JSON arrays are not allowed in FHIR"));
        return;
      }
      if (!"*".equals(rule.max())) {
        int maxInt = parseMax(rule.max());
        if (arr.elements().size() > maxInt) {
          issues.add(
              new Issue(
                  "error",
                  "structure",
                  propExpr,
                  "Array length exceeds max cardinality " + maxInt));
        }
      }
      for (JsonValue item : arr.elements()) {
        if (item instanceof JsonNull) {
          issues.add(
              new Issue(
                  "error", "structure", propExpr, "Null elements are not allowed in FHIR arrays"));
          continue;
        }
        validateSingleItem(item, rule, chosenType, propExpr, allParentRules, depth, issues);
      }
    } else {
      if (propValue instanceof JsonArray) {
        issues.add(
            new Issue(
                "error",
                "structure",
                propExpr,
                "Element " + propExpr + " has max " + rule.max() + " but was given as an array"));
        return;
      }
      if ("0".equals(rule.max())) {
        issues.add(
            new Issue(
                "error", "structure", propExpr, "Element " + propExpr + " is prohibited (max 0)"));
        return;
      }
      validateSingleItem(propValue, rule, chosenType, propExpr, allParentRules, depth, issues);
    }
  }

  private void validateSingleItem(
      JsonValue item,
      ElementRule rule,
      String chosenType,
      String propExpr,
      List<ElementRule> allParentRules,
      int depth,
      List<Issue> issues) {
    if ("Resource".equals(chosenType)) {
      if (!(item instanceof JsonObject containedObj)) {
        issues.add(
            new Issue("error", "structure", propExpr, "Contained resource must be a JSON object"));
        return;
      }
      JsonValue rtVal = containedObj.members().get("resourceType");
      if (!(rtVal instanceof JsonString rtStr) || rtStr.value().isEmpty()) {
        issues.add(
            new Issue(
                "error",
                "structure",
                propExpr,
                "Contained resource is missing a valid resourceType"));
        return;
      }
      StructureDef containedDef = definitions.get(rtStr.value());
      if (containedDef == null) {
        throw new FhirException(
            "unknown_resource_type", "Unknown contained resourceType: " + rtStr.value());
      }
      validateObject(
          containedObj,
          containedDef.type(),
          propExpr,
          containedDef.rules(),
          true,
          depth + 1,
          issues);
      return;
    }

    if (PrimitiveValidators.isPrimitiveType(chosenType)) {
      if (item instanceof JsonObject || item instanceof JsonArray) {
        issues.add(
            new Issue(
                "error",
                "structure",
                propExpr,
                "Expected primitive value of type " + chosenType + " at " + propExpr));
        return;
      }
      if (!PrimitiveValidators.isValidPrimitive(chosenType, item)) {
        issues.add(
            new Issue(
                "error",
                "value",
                propExpr,
                "Value at " + propExpr + " is not a valid FHIR " + chosenType));
        return;
      }
      if (item instanceof JsonString js) {
        checkRequiredBinding(js.value(), rule, propExpr, issues);
      }
      return;
    }

    // Complex type or BackboneElement
    if (!(item instanceof JsonObject complexObj)) {
      issues.add(
          new Issue(
              "error",
              "structure",
              propExpr,
              "Expected JSON object for complex type " + chosenType + " at " + propExpr));
      return;
    }

    if ("BackboneElement".equals(chosenType) || "Element".equals(chosenType)) {
      List<ElementRule> inlineChildren = directChildRules(rule.path(), allParentRules);
      if (!inlineChildren.isEmpty()) {
        validateObject(complexObj, rule.path(), propExpr, allParentRules, false, depth + 1, issues);
      }
      checkComplexBinding(complexObj, chosenType, rule, propExpr, issues);
      return;
    }

    StructureDef typeDef = definitions.get(chosenType);
    if (typeDef != null) {
      validateObject(
          complexObj, typeDef.type(), propExpr, typeDef.rules(), false, depth + 1, issues);
    } else {
      List<ElementRule> inlineChildren = directChildRules(rule.path(), allParentRules);
      if (!inlineChildren.isEmpty()) {
        validateObject(complexObj, rule.path(), propExpr, allParentRules, false, depth + 1, issues);
      }
    }
    checkComplexBinding(complexObj, chosenType, rule, propExpr, issues);
  }

  private void checkRequiredBinding(
      String codeValue, ElementRule rule, String propExpr, List<Issue> issues) {
    if (!"required".equals(rule.bindingStrength()) || rule.valueSetUrl() == null) {
      return;
    }
    Set<String> allowedCodes = valueSets.get(rule.valueSetUrl());
    if (allowedCodes != null && !allowedCodes.contains(codeValue)) {
      issues.add(
          new Issue(
              "error",
              "code-invalid",
              propExpr,
              "Code '" + codeValue + "' is not in required ValueSet " + rule.valueSetUrl()));
    }
  }

  private void checkComplexBinding(
      JsonObject complexObj,
      String chosenType,
      ElementRule rule,
      String propExpr,
      List<Issue> issues) {
    if (!"required".equals(rule.bindingStrength()) || rule.valueSetUrl() == null) {
      return;
    }
    Set<String> allowedCodes = valueSets.get(rule.valueSetUrl());
    if (allowedCodes == null) {
      return;
    }
    if ("Coding".equals(chosenType)) {
      JsonValue codeVal = complexObj.members().get("code");
      if (codeVal instanceof JsonString js && !allowedCodes.contains(js.value())) {
        issues.add(
            new Issue(
                "error",
                "code-invalid",
                propExpr,
                "Coding.code '"
                    + js.value()
                    + "' is not in required ValueSet "
                    + rule.valueSetUrl()));
      }
    } else if ("CodeableConcept".equals(chosenType)) {
      JsonValue codingVal = complexObj.members().get("coding");
      if (codingVal instanceof JsonArray arr && !arr.elements().isEmpty()) {
        boolean anyMatch = false;
        boolean anyCodePresent = false;
        for (JsonValue el : arr.elements()) {
          if (el instanceof JsonObject cObj
              && cObj.members().get("code") instanceof JsonString js) {
            anyCodePresent = true;
            if (allowedCodes.contains(js.value())) {
              anyMatch = true;
              break;
            }
          }
        }
        if (anyCodePresent && !anyMatch) {
          issues.add(
              new Issue(
                  "error",
                  "code-invalid",
                  propExpr,
                  "None of the codes in CodeableConcept match required ValueSet "
                      + rule.valueSetUrl()));
        }
      }
    }
  }

  private record ResolvedProperty(ElementRule rule, String chosenType) {}

  private static ResolvedProperty resolveProperty(
      String propName, String defRootPath, List<ElementRule> directRules, boolean isResource) {
    for (ElementRule rule : directRules) {
      int lastDot = rule.path().lastIndexOf('.');
      String childSpec = lastDot >= 0 ? rule.path().substring(lastDot + 1) : rule.path();
      if (childSpec.endsWith("[x]")) {
        String prefix = childSpec.substring(0, childSpec.length() - 3);
        if (propName.startsWith(prefix) && propName.length() > prefix.length()) {
          String suffix = propName.substring(prefix.length());
          for (String typeCode : rule.types()) {
            if (capitalize(typeCode).equals(suffix)) {
              return new ResolvedProperty(rule, typeCode);
            }
          }
        }
      } else if (childSpec.equals(propName)) {
        String chosenType = rule.types().isEmpty() ? "BackboneElement" : rule.types().get(0);
        return new ResolvedProperty(rule, chosenType);
      }
    }

    if (isResource && IMPLICIT_RESOURCE_ELEMENTS.contains(propName)) {
      ElementRule implicit = IMPLICIT_RESOURCE_RULES.get(propName);
      if (implicit != null) {
        return new ResolvedProperty(implicit, implicit.types().get(0));
      }
    }
    if (!isResource && ("id".equals(propName) || "extension".equals(propName))) {
      if ("id".equals(propName)) {
        ElementRule idRule =
            new ElementRule(defRootPath + ".id", 0, "1", List.of("string"), null, null);
        return new ResolvedProperty(idRule, "string");
      }
      ElementRule extRule =
          new ElementRule(defRootPath + ".extension", 0, "*", List.of("Extension"), null, null);
      return new ResolvedProperty(extRule, "Extension");
    }
    return null;
  }

  private static int countMatchingElements(JsonObject obj, String childSpec, ElementRule rule) {
    if (childSpec.endsWith("[x]")) {
      String prefix = childSpec.substring(0, childSpec.length() - 3);
      int total = 0;
      for (String typeCode : rule.types()) {
        String candidate = prefix + capitalize(typeCode);
        JsonValue val = obj.members().get(candidate);
        total += countValueOccurrences(val);
      }
      return total;
    }
    JsonValue val = obj.members().get(childSpec);
    return countValueOccurrences(val);
  }

  private static int countValueOccurrences(JsonValue val) {
    if (val == null || val instanceof JsonNull) {
      return 0;
    }
    if (val instanceof JsonArray arr) {
      return arr.elements().size();
    }
    return 1;
  }

  private static List<ElementRule> directChildRules(String parentPath, List<ElementRule> allRules) {
    String prefix = parentPath + ".";
    List<ElementRule> result = new ArrayList<>();
    for (ElementRule rule : allRules) {
      String p = rule.path();
      if (p.startsWith(prefix) && p.indexOf('.', prefix.length()) < 0) {
        result.add(rule);
      }
    }
    return result;
  }

  private static boolean isRepeatable(String max) {
    if ("*".equals(max)) {
      return true;
    }
    return parseMax(max) > 1;
  }

  private static int parseMax(String max) {
    try {
      return Integer.parseInt(max);
    } catch (NumberFormatException e) {
      throw new FhirException("invalid_definition", "Invalid max cardinality: " + max);
    }
  }

  private static String capitalize(String s) {
    if (s.isEmpty()) {
      return s;
    }
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private static StructureDef parseStructureDefinition(byte[] raw) {
    JsonValue parsed = JsonParser.parse(raw);
    if (!(parsed instanceof JsonObject root)) {
      throw new FhirException("invalid_definition", "StructureDefinition must be a JSON object");
    }
    JsonValue rt = root.members().get("resourceType");
    if (!(rt instanceof JsonString rtStr) || !"StructureDefinition".equals(rtStr.value())) {
      throw new FhirException("invalid_definition", "Expected resourceType 'StructureDefinition'");
    }
    String typeName = extractString(root, "type");
    if (typeName == null || typeName.isEmpty()) {
      typeName = extractString(root, "name");
    }
    if (typeName == null || typeName.isEmpty()) {
      throw new FhirException("invalid_definition", "StructureDefinition missing 'type' or 'name'");
    }
    JsonValue snapshotVal = root.members().get("snapshot");
    if (!(snapshotVal instanceof JsonObject snapshotObj)) {
      throw new FhirException(
          "invalid_definition", "StructureDefinition missing 'snapshot' object");
    }
    JsonValue elementsVal = snapshotObj.members().get("element");
    if (!(elementsVal instanceof JsonArray elementsArr) || elementsArr.elements().isEmpty()) {
      throw new FhirException(
          "invalid_definition", "StructureDefinition snapshot.element must be a non-empty array");
    }

    List<ElementRule> rules = new ArrayList<>();
    for (JsonValue elVal : elementsArr.elements()) {
      if (!(elVal instanceof JsonObject elObj)) {
        throw new FhirException(
            "invalid_definition", "Element in snapshot.element must be a JSON object");
      }
      String path = extractString(elObj, "path");
      if (path == null || path.isEmpty()) {
        throw new FhirException("invalid_definition", "Element definition missing 'path'");
      }
      if (path.equals(typeName)) {
        continue;
      }
      int min = extractMin(elObj);
      String max = extractString(elObj, "max");
      if (max == null || max.isEmpty()) {
        max = "1";
      }
      if (!"*".equals(max)) {
        int maxVal = parseMax(max);
        if (maxVal < 0 || maxVal < min) {
          throw new FhirException(
              "invalid_definition",
              "Invalid cardinality on " + path + ": min=" + min + " max=" + max);
        }
      }
      List<String> types = extractTypes(elObj);
      String bindingStrength = null;
      String valueSetUrl = null;
      JsonValue bindingVal = elObj.members().get("binding");
      if (bindingVal instanceof JsonObject bindingObj) {
        bindingStrength = extractString(bindingObj, "strength");
        String rawVs = extractString(bindingObj, "valueSet");
        if (rawVs != null) {
          valueSetUrl = stripVersion(rawVs);
        }
      }
      rules.add(new ElementRule(path, min, max, types, bindingStrength, valueSetUrl));
    }
    return new StructureDef(typeName, List.copyOf(rules));
  }

  private static int extractMin(JsonObject elObj) {
    JsonValue minVal = elObj.members().get("min");
    if (minVal == null) {
      return 0;
    }
    if (!(minVal instanceof JsonParser.JsonNumber num)) {
      throw new FhirException("invalid_definition", "Element 'min' must be an integer");
    }
    try {
      int min = num.value().intValueExact();
      if (min < 0) {
        throw new FhirException("invalid_definition", "Element 'min' must be non-negative");
      }
      return min;
    } catch (ArithmeticException e) {
      throw new FhirException("invalid_definition", "Element 'min' is not a valid integer");
    }
  }

  private static List<String> extractTypes(JsonObject elObj) {
    JsonValue typeVal = elObj.members().get("type");
    if (typeVal == null) {
      return List.of();
    }
    if (!(typeVal instanceof JsonArray typeArr)) {
      throw new FhirException("invalid_definition", "Element 'type' must be an array");
    }
    List<String> types = new ArrayList<>();
    for (JsonValue item : typeArr.elements()) {
      if (!(item instanceof JsonObject tObj)) {
        throw new FhirException("invalid_definition", "Element type entry must be an object");
      }
      String code = extractString(tObj, "code");
      if (code == null || code.isEmpty()) {
        throw new FhirException("invalid_definition", "Element type entry missing 'code'");
      }
      types.add(code);
    }
    return types;
  }

  private static void parseValueSetExpansion(byte[] raw, Map<String, Set<String>> vsMap) {
    JsonValue parsed = JsonParser.parse(raw);
    if (!(parsed instanceof JsonObject root)) {
      throw new FhirException("invalid_definition", "ValueSet must be a JSON object");
    }
    JsonValue rt = root.members().get("resourceType");
    if (!(rt instanceof JsonString rtStr) || !"ValueSet".equals(rtStr.value())) {
      throw new FhirException("invalid_definition", "Expected resourceType 'ValueSet'");
    }
    String url = extractString(root, "url");
    if (url == null || url.isEmpty()) {
      throw new FhirException("invalid_definition", "ValueSet missing 'url'");
    }
    String canonicalUrl = stripVersion(url);
    Set<String> codes = new LinkedHashSet<>();

    JsonValue expVal = root.members().get("expansion");
    if (expVal instanceof JsonObject expObj) {
      JsonValue containsVal = expObj.members().get("contains");
      if (containsVal instanceof JsonArray containsArr) {
        collectExpansionCodes(containsArr, codes);
      }
    }

    JsonValue composeVal = root.members().get("compose");
    if (composeVal instanceof JsonObject composeObj) {
      JsonValue includeVal = composeObj.members().get("include");
      if (includeVal instanceof JsonArray includeArr) {
        for (JsonValue incItem : includeArr.elements()) {
          if (incItem instanceof JsonObject incObj
              && incObj.members().get("concept") instanceof JsonArray conceptArr) {
            for (JsonValue cItem : conceptArr.elements()) {
              if (cItem instanceof JsonObject cObj) {
                String code = extractString(cObj, "code");
                if (code != null) {
                  codes.add(code);
                }
              }
            }
          }
        }
      }
    }

    if (codes.isEmpty()) {
      throw new FhirException(
          "invalid_definition", "ValueSet '" + canonicalUrl + "' has no codes in expansion");
    }
    vsMap.put(canonicalUrl, Set.copyOf(codes));
  }

  private static void collectExpansionCodes(JsonArray containsArr, Set<String> codes) {
    for (JsonValue item : containsArr.elements()) {
      if (!(item instanceof JsonObject itemObj)) {
        throw new FhirException(
            "invalid_definition", "ValueSet expansion.contains entry must be an object");
      }
      String code = extractString(itemObj, "code");
      if (code != null && !code.isEmpty()) {
        codes.add(code);
      }
      JsonValue nested = itemObj.members().get("contains");
      if (nested instanceof JsonArray nestedArr) {
        collectExpansionCodes(nestedArr, codes);
      }
    }
  }

  private static String extractString(JsonObject obj, String key) {
    JsonValue val = obj.members().get(key);
    if (val == null) {
      return null;
    }
    if (val instanceof JsonString js) {
      return js.value();
    }
    throw new FhirException(
        "invalid_definition", String.format(Locale.ROOT, "Expected string for field '%s'", key));
  }

  private static String stripVersion(String url) {
    int pipeIdx = url.indexOf('|');
    return pipeIdx >= 0 ? url.substring(0, pipeIdx) : url;
  }
}
