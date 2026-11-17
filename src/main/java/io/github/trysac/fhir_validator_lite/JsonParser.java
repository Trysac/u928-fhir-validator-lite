package io.github.trysac.fhir_validator_lite;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict RFC 8259 JSON parser keeping numbers as {@link BigDecimal}, rejecting duplicate object
 * keys, and enforcing byte size and nesting depth limits.
 */
final class JsonParser {

  static final int MAX_INPUT_BYTES = 4_194_304;
  static final int MAX_DEPTH = 64;

  sealed interface JsonValue
      permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {}

  record JsonObject(Map<String, JsonValue> members) implements JsonValue {}

  record JsonArray(List<JsonValue> elements) implements JsonValue {}

  record JsonString(String value) implements JsonValue {}

  record JsonNumber(BigDecimal value, String raw) implements JsonValue {}

  record JsonBoolean(boolean value) implements JsonValue {}

  record JsonNull() implements JsonValue {
    static final JsonNull INSTANCE = new JsonNull();
  }

  private final String src;
  private final int len;
  private int pos;

  private JsonParser(String src) {
    this.src = src;
    this.len = src.length();
    this.pos = 0;
  }

  static JsonValue parse(byte[] bytes) {
    if (bytes == null) {
      throw new NullPointerException("bytes");
    }
    if (bytes.length > MAX_INPUT_BYTES) {
      throw new FhirException(
          "input_too_large", "Input size " + bytes.length + " exceeds limit " + MAX_INPUT_BYTES);
    }
    String decoded = decodeUtf8(bytes);
    JsonParser parser = new JsonParser(decoded);
    parser.skipWhitespace();
    if (parser.pos >= parser.len) {
      throw new FhirException("invalid_json", "Empty JSON input");
    }
    JsonValue value = parser.parseValue(0);
    parser.skipWhitespace();
    if (parser.pos != parser.len) {
      throw new FhirException("invalid_json", "Trailing characters after JSON root value");
    }
    return value;
  }

  private static String decodeUtf8(byte[] bytes) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      CharBuffer cb = decoder.decode(ByteBuffer.wrap(bytes));
      return cb.toString();
    } catch (CharacterCodingException e) {
      throw new FhirException("invalid_json", "Invalid UTF-8 byte sequence");
    }
  }

  private JsonValue parseValue(int depth) {
    skipWhitespace();
    if (pos >= len) {
      throw new FhirException("invalid_json", "Unexpected end of JSON input");
    }
    char c = src.charAt(pos);
    return switch (c) {
      case '{' -> parseObject(depth + 1);
      case '[' -> parseArray(depth + 1);
      case '"' -> new JsonString(parseString());
      case 't' -> parseTrue();
      case 'f' -> parseFalse();
      case 'n' -> parseNull();
      default -> {
        if (c == '-' || (c >= '0' && c <= '9')) {
          yield parseNumber();
        }
        throw new FhirException("invalid_json", "Unexpected character at position " + pos);
      }
    };
  }

  private JsonObject parseObject(int depth) {
    if (depth > MAX_DEPTH) {
      throw new FhirException("too_deep", "JSON nesting depth exceeds maximum of " + MAX_DEPTH);
    }
    pos++; // consume '{'
    skipWhitespace();
    Map<String, JsonValue> map = new LinkedHashMap<>();
    if (pos < len && src.charAt(pos) == '}') {
      pos++;
      return new JsonObject(map);
    }
    while (true) {
      skipWhitespace();
      if (pos >= len || src.charAt(pos) != '"') {
        throw new FhirException("invalid_json", "Expected object key string at position " + pos);
      }
      String key = parseString();
      if (map.containsKey(key)) {
        throw new FhirException("invalid_json", "Duplicate JSON object key: " + key);
      }
      skipWhitespace();
      if (pos >= len || src.charAt(pos) != ':') {
        throw new FhirException("invalid_json", "Expected ':' after object key at position " + pos);
      }
      pos++; // consume ':'
      JsonValue val = parseValue(depth);
      map.put(key, val);
      skipWhitespace();
      if (pos >= len) {
        throw new FhirException("invalid_json", "Unterminated JSON object");
      }
      char next = src.charAt(pos);
      if (next == '}') {
        pos++;
        return new JsonObject(map);
      }
      if (next != ',') {
        throw new FhirException("invalid_json", "Expected ',' or '}' in object at position " + pos);
      }
      pos++; // consume ','
    }
  }

  private JsonArray parseArray(int depth) {
    if (depth > MAX_DEPTH) {
      throw new FhirException("too_deep", "JSON nesting depth exceeds maximum of " + MAX_DEPTH);
    }
    pos++; // consume '['
    skipWhitespace();
    List<JsonValue> list = new ArrayList<>();
    if (pos < len && src.charAt(pos) == ']') {
      pos++;
      return new JsonArray(list);
    }
    while (true) {
      JsonValue element = parseValue(depth);
      list.add(element);
      skipWhitespace();
      if (pos >= len) {
        throw new FhirException("invalid_json", "Unterminated JSON array");
      }
      char next = src.charAt(pos);
      if (next == ']') {
        pos++;
        return new JsonArray(list);
      }
      if (next != ',') {
        throw new FhirException("invalid_json", "Expected ',' or ']' in array at position " + pos);
      }
      pos++; // consume ','
    }
  }

  private String parseString() {
    pos++; // consume opening '"'
    StringBuilder sb = new StringBuilder();
    while (pos < len) {
      char c = src.charAt(pos++);
      if (c == '"') {
        return sb.toString();
      }
      if (c < 0x20) {
        throw new FhirException("invalid_json", "Unescaped control character in string");
      }
      if (c == '\\') {
        if (pos >= len) {
          throw new FhirException("invalid_json", "Unterminated escape sequence in string");
        }
        char esc = src.charAt(pos++);
        switch (esc) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/' -> sb.append('/');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            char codeUnit = parseHexQuad();
            if (Character.isHighSurrogate(codeUnit)) {
              if (pos + 1 < len && src.charAt(pos) == '\\' && src.charAt(pos + 1) == 'u') {
                pos += 2;
                char low = parseHexQuad();
                if (!Character.isLowSurrogate(low)) {
                  throw new FhirException(
                      "invalid_json", "Invalid low surrogate in unicode escape");
                }
                sb.append(codeUnit).append(low);
              } else {
                throw new FhirException(
                    "invalid_json", "Unpaired high surrogate in unicode escape");
              }
            } else if (Character.isLowSurrogate(codeUnit)) {
              throw new FhirException("invalid_json", "Unpaired low surrogate in unicode escape");
            } else {
              sb.append(codeUnit);
            }
          }
          default -> throw new FhirException("invalid_json", "Invalid escape character: \\" + esc);
        }
      } else {
        sb.append(c);
      }
    }
    throw new FhirException("invalid_json", "Unterminated string literal");
  }

  private char parseHexQuad() {
    if (pos + 4 > len) {
      throw new FhirException("invalid_json", "Incomplete unicode escape sequence");
    }
    int value = 0;
    for (int i = 0; i < 4; i++) {
      char ch = src.charAt(pos++);
      int digit = Character.digit(ch, 16);
      if (digit < 0) {
        throw new FhirException("invalid_json", "Invalid hex digit in unicode escape");
      }
      value = (value << 4) | digit;
    }
    return (char) value;
  }

  private JsonNumber parseNumber() {
    int start = pos;
    if (src.charAt(pos) == '-') {
      pos++;
      if (pos >= len) {
        throw new FhirException("invalid_json", "Incomplete negative number");
      }
    }
    char firstDigit = src.charAt(pos);
    if (firstDigit == '0') {
      pos++;
      if (pos < len && isAsciiDigit(src.charAt(pos))) {
        throw new FhirException("invalid_json", "Leading zeros are not allowed in JSON numbers");
      }
    } else if (firstDigit >= '1' && firstDigit <= '9') {
      pos++;
      while (pos < len && isAsciiDigit(src.charAt(pos))) {
        pos++;
      }
    } else {
      throw new FhirException("invalid_json", "Invalid number at position " + pos);
    }
    if (pos < len && src.charAt(pos) == '.') {
      pos++;
      if (pos >= len || !isAsciiDigit(src.charAt(pos))) {
        throw new FhirException("invalid_json", "Expected digit after decimal point");
      }
      while (pos < len && isAsciiDigit(src.charAt(pos))) {
        pos++;
      }
    }
    if (pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
      pos++;
      if (pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
        pos++;
      }
      if (pos >= len || !isAsciiDigit(src.charAt(pos))) {
        throw new FhirException("invalid_json", "Expected digit in number exponent");
      }
      while (pos < len && isAsciiDigit(src.charAt(pos))) {
        pos++;
      }
    }
    String raw = src.substring(start, pos);
    try {
      return new JsonNumber(new BigDecimal(raw), raw);
    } catch (NumberFormatException e) {
      throw new FhirException("invalid_json", "Invalid numeric value: " + raw);
    }
  }

  private JsonBoolean parseTrue() {
    if (src.startsWith("true", pos)) {
      pos += 4;
      return new JsonBoolean(true);
    }
    throw new FhirException("invalid_json", "Invalid literal starting with 't'");
  }

  private JsonBoolean parseFalse() {
    if (src.startsWith("false", pos)) {
      pos += 5;
      return new JsonBoolean(false);
    }
    throw new FhirException("invalid_json", "Invalid literal starting with 'f'");
  }

  private JsonNull parseNull() {
    if (src.startsWith("null", pos)) {
      pos += 4;
      return JsonNull.INSTANCE;
    }
    throw new FhirException("invalid_json", "Invalid literal starting with 'n'");
  }

  private void skipWhitespace() {
    while (pos < len) {
      char c = src.charAt(pos);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        pos++;
      } else {
        break;
      }
    }
  }

  private static boolean isAsciiDigit(char c) {
    return c >= '0' && c <= '9';
  }

  static String escapeJsonString(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
    return sb.toString();
  }
}
