package io.github.connellite.mapper.xml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * XSD built-in simple values between their JSON form and their XML lexical form. JSON keeps what the REST API
 * has always shown: numbers and booleans typed, binary content as base64 (also for {@code xs:hexBinary}), every
 * other value as its lexical string.
 */
@UtilityClass
public class XsdValues {

    private static final Set<String> INTEGERS = Set.of("byte", "short", "int", "integer", "long", "unsignedbyte",
            "unsignedshort", "unsignedint", "unsignedlong", "nonnegativeinteger", "nonpositiveinteger",
            "positiveinteger", "negativeinteger");
    private static final Set<String> FLOATS = Set.of("float", "double");
    /** Values whose whitespace XSD collapses: everything but strings keeps no leading or trailing blanks. */
    private static final Set<String> PRESERVED = Set.of("string", "anysimpletype", "anytype");

    public static String normalizeType(String xsdType) {
        if (xsdType == null || xsdType.isBlank()) {
            return "string";
        }
        int colon = xsdType.indexOf(':');
        return (colon >= 0 ? xsdType.substring(colon + 1) : xsdType).toLowerCase(Locale.ROOT);
    }

    /**
     * The lexical XML form of a JSON scalar.
     *
     * @throws IllegalArgumentException when the value is not one of the type, e.g. {@code "abc"} for an
     *                                  {@code xs:int}; the message names the expected type
     */
    public static String toXml(JsonNode value, String xsdType) {
        String type = normalizeType(xsdType);
        if (value.isContainerNode()) {
            throw new IllegalArgumentException(jsonType(value) + " cannot be converted to " + xsdType);
        }
        String text = value.asText();
        if (INTEGERS.contains(type)) {
            if (value.isIntegralNumber()) {
                return value.bigIntegerValue().toString();
            }
            try {
                return new BigDecimal(text.trim()).toBigIntegerExact().toString();
            } catch (ArithmeticException | NumberFormatException exception) {
                throw new IllegalArgumentException(jsonType(value) + " cannot be converted to " + xsdType);
            }
        }
        if ("decimal".equals(type)) {
            try {
                return (value.isNumber() ? value.decimalValue() : new BigDecimal(text.trim())).toPlainString();
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(jsonType(value) + " cannot be converted to " + xsdType);
            }
        }
        if (FLOATS.contains(type)) {
            String trimmed = text.trim();
            if (value.isNumber() || Set.of("INF", "-INF", "NaN").contains(trimmed)) {
                return trimmed;
            }
            try {
                Double.parseDouble(trimmed);
                return trimmed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(jsonType(value) + " cannot be converted to " + xsdType);
            }
        }
        if ("boolean".equals(type)) {
            String lower = text.trim().toLowerCase(Locale.ROOT);
            if (Set.of("true", "false", "1", "0").contains(lower)) {
                return lower;
            }
            throw new IllegalArgumentException(jsonType(value) + " cannot be converted to " + xsdType);
        }
        if ("hexbinary".equals(type)) {
            try {
                return HexFormat.of().withUpperCase().formatHex(Base64.getMimeDecoder().decode(text));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(jsonType(value) + " is not base64 content for " + xsdType);
            }
        }
        return PRESERVED.contains(type) ? text : text.trim();
    }

    /** The JSON form of a lexical XML value; a value that is not of its type stays a string. */
    public static JsonNode toJson(String lexical, String xsdType) {
        String type = normalizeType(xsdType);
        String trimmed = lexical.trim();
        try {
            if (INTEGERS.contains(type)) {
                BigInteger integer = new BigInteger(trimmed.startsWith("+") ? trimmed.substring(1) : trimmed);
                if (integer.bitLength() < Integer.SIZE) {
                    return IntNode.valueOf(integer.intValue());
                }
                return integer.bitLength() < Long.SIZE ? LongNode.valueOf(integer.longValue()) : BigIntegerNode.valueOf(integer);
            }
            if ("decimal".equals(type)) {
                return DecimalNode.valueOf(new BigDecimal(trimmed));
            }
            if (FLOATS.contains(type) && !Set.of("INF", "-INF", "NaN").contains(trimmed)) {
                return DoubleNode.valueOf(Double.parseDouble(trimmed));
            }
        } catch (NumberFormatException exception) {
            return TextNode.valueOf(lexical);
        }
        if ("boolean".equals(type) && Set.of("true", "false", "1", "0").contains(trimmed)) {
            return BooleanNode.valueOf("true".equals(trimmed) || "1".equals(trimmed));
        }
        if ("hexbinary".equals(type)) {
            try {
                return TextNode.valueOf(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(trimmed)));
            } catch (IllegalArgumentException exception) {
                return TextNode.valueOf(lexical);
            }
        }
        if ("base64binary".equals(type)) {
            return TextNode.valueOf(trimmed.replaceAll("\\s+", ""));
        }
        return TextNode.valueOf(PRESERVED.contains(type) ? lexical : trimmed);
    }

    public static String jsonType(JsonNode value) {
        if (value.isObject()) {
            return "object";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        return "string";
    }
}
