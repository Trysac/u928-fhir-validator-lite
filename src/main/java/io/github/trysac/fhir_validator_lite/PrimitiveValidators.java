package io.github.trysac.fhir_validator_lite;

import io.github.trysac.fhir_validator_lite.JsonParser.JsonBoolean;
import io.github.trysac.fhir_validator_lite.JsonParser.JsonNumber;
import io.github.trysac.fhir_validator_lite.JsonParser.JsonString;
import io.github.trysac.fhir_validator_lite.JsonParser.JsonValue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FHIR R4 primitive datatype validators (section 2.24 of the R4 datatypes specification). */
final class PrimitiveValidators {

  private static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
  private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
  private static final BigDecimal POSITIVE_INT_MIN = BigDecimal.ONE;
  private static final BigDecimal UNSIGNED_INT_MIN = BigDecimal.ZERO;

  private static final Pattern CODE_PATTERN = Pattern.compile("[^\\s]+(\\s[^\\s]+)*");
  private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9.-]{1,64}");

  private static final String YEAR_REGEX = "([0-9]([0-9]([0-9][1-9]|[1-9]0)|[1-9]00)|[1-9]000)";
  private static final String MONTH_REGEX = "(0[1-9]|1[0-2])";
  private static final String DAY_REGEX = "(0[1-9]|[1-2][0-9]|3[0-1])";
  private static final String TIME_REGEX =
      "([01][0-9]|2[0-3]):[0-5][0-9]:([0-5][0-9]|60)(\\.[0-9]+)?";
  private static final String TZ_REGEX = "(Z|(\\+|-)((0[0-9]|1[0-3]):[0-5][0-9]|14:00))";

  private static final Pattern DATE_PATTERN =
      Pattern.compile("^" + YEAR_REGEX + "(-" + MONTH_REGEX + "(-" + DAY_REGEX + ")?)?$");

  private static final Pattern DATETIME_PATTERN =
      Pattern.compile(
          "^"
              + YEAR_REGEX
              + "(-"
              + MONTH_REGEX
              + "(-"
              + DAY_REGEX
              + "(T"
              + TIME_REGEX
              + TZ_REGEX
              + ")?)?)?$");

  private static final Pattern INSTANT_PATTERN =
      Pattern.compile(
          "^"
              + YEAR_REGEX
              + "-"
              + MONTH_REGEX
              + "-"
              + DAY_REGEX
              + "T"
              + TIME_REGEX
              + TZ_REGEX
              + "$");

  private static final Pattern TIME_PATTERN = Pattern.compile("^" + TIME_REGEX + "$");

  private static final Set<String> PRIMITIVE_TYPES =
      Set.of(
          "boolean",
          "integer",
          "positiveInt",
          "unsignedInt",
          "decimal",
          "string",
          "markdown",
          "code",
          "id",
          "date",
          "dateTime",
          "instant",
          "time",
          "uri",
          "url",
          "canonical",
          "oid",
          "uuid",
          "base64Binary",
          "xhtml");

  private PrimitiveValidators() {}

  static boolean isPrimitiveType(String typeCode) {
    if (typeCode == null) {
      return false;
    }
    if (typeCode.startsWith("http://hl7.org/fhirpath/System.")) {
      return true;
    }
    return PRIMITIVE_TYPES.contains(typeCode);
  }

  static boolean isValidPrimitive(String typeCode, JsonValue value) {
    String normalized =
        typeCode.startsWith("http://hl7.org/fhirpath/System.") ? "string" : typeCode;
    return switch (normalized) {
      case "boolean" -> value instanceof JsonBoolean;
      case "integer" -> isValidInteger(value, INT_MIN, INT_MAX);
      case "positiveInt" -> isValidInteger(value, POSITIVE_INT_MIN, INT_MAX);
      case "unsignedInt" -> isValidInteger(value, UNSIGNED_INT_MIN, INT_MAX);
      case "decimal" -> value instanceof JsonNumber;
      case "string", "markdown", "xhtml", "base64Binary" ->
          value instanceof JsonString js && !js.value().isEmpty();
      case "code" ->
          value instanceof JsonString js
              && !js.value().isEmpty()
              && CODE_PATTERN.matcher(js.value()).matches();
      case "id" -> value instanceof JsonString js && ID_PATTERN.matcher(js.value()).matches();
      case "date" -> value instanceof JsonString js && isValidDate(js.value());
      case "dateTime" -> value instanceof JsonString js && isValidDateTime(js.value());
      case "instant" -> value instanceof JsonString js && isValidInstant(js.value());
      case "time" -> value instanceof JsonString js && TIME_PATTERN.matcher(js.value()).matches();
      case "uri", "url", "canonical", "oid", "uuid" ->
          value instanceof JsonString js && isValidUri(js.value());
      default -> false;
    };
  }

  private static boolean isValidInteger(JsonValue value, BigDecimal min, BigDecimal max) {
    if (!(value instanceof JsonNumber num)) {
      return false;
    }
    String raw = num.raw();
    if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
      return false;
    }
    BigDecimal bd = num.value();
    return bd.compareTo(min) >= 0 && bd.compareTo(max) <= 0;
  }

  private static boolean isValidDate(String s) {
    Matcher m = DATE_PATTERN.matcher(s);
    if (!m.matches()) {
      return false;
    }
    return checkCalendarDate(s);
  }

  private static boolean isValidDateTime(String s) {
    Matcher m = DATETIME_PATTERN.matcher(s);
    if (!m.matches()) {
      return false;
    }
    int tIdx = s.indexOf('T');
    String datePart = tIdx >= 0 ? s.substring(0, tIdx) : s;
    return checkCalendarDate(datePart);
  }

  private static boolean isValidInstant(String s) {
    Matcher m = INSTANT_PATTERN.matcher(s);
    if (!m.matches()) {
      return false;
    }
    int tIdx = s.indexOf('T');
    return checkCalendarDate(s.substring(0, tIdx));
  }

  private static boolean checkCalendarDate(String datePart) {
    try {
      if (datePart.length() == 4) {
        return true;
      }
      if (datePart.length() == 7) {
        YearMonth.parse(datePart);
        return true;
      }
      if (datePart.length() == 10) {
        LocalDate.parse(datePart);
        return true;
      }
      return false;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static boolean isValidUri(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (Character.isWhitespace(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
