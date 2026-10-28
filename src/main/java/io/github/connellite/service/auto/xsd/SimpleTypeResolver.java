package io.github.connellite.service.auto.xsd;

import io.github.connellite.model.SchemaFacets;
import lombok.experimental.UtilityClass;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaEnumerationFacet;
import org.apache.ws.commons.schema.XmlSchemaFacet;
import org.apache.ws.commons.schema.XmlSchemaLengthFacet;
import org.apache.ws.commons.schema.XmlSchemaMaxExclusiveFacet;
import org.apache.ws.commons.schema.XmlSchemaMaxInclusiveFacet;
import org.apache.ws.commons.schema.XmlSchemaMaxLengthFacet;
import org.apache.ws.commons.schema.XmlSchemaMinExclusiveFacet;
import org.apache.ws.commons.schema.XmlSchemaMinInclusiveFacet;
import org.apache.ws.commons.schema.XmlSchemaMinLengthFacet;
import org.apache.ws.commons.schema.XmlSchemaPatternFacet;
import org.apache.ws.commons.schema.XmlSchemaSimpleType;
import org.apache.ws.commons.schema.XmlSchemaSimpleTypeContent;
import org.apache.ws.commons.schema.XmlSchemaSimpleTypeList;
import org.apache.ws.commons.schema.XmlSchemaSimpleTypeRestriction;
import org.apache.ws.commons.schema.XmlSchemaType;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a simple type (named or anonymous, any depth of {@code xs:restriction}) to the built-in XSD type it
 * is derived from plus the facets that apply to it, e.g. {@code tns:Level} → {@code string} with
 * {@code enumeration [GOLD, PLATINUM]}. Unions become {@code string}; lists become collections of their item type.
 */
@UtilityClass
public class SimpleTypeResolver {

    private static final int MAX_DERIVATION_DEPTH = 32;

    /**
     * @param list {@code xs:list}: a JSON array of {@code xsdType} values (JAXB {@code @XmlList List<T>})
     */
    public record SimpleTypeInfo(String xsdType, SchemaFacets facets, boolean list) {

        static final SimpleTypeInfo STRING = new SimpleTypeInfo("string", SchemaFacets.NONE, false);
    }

    public static SimpleTypeInfo resolve(QName typeName, XmlSchemaType inlineType, XmlSchema schema) {
        return resolve(typeName, inlineType, schema, 0);
    }

    private static SimpleTypeInfo resolve(QName typeName, XmlSchemaType inlineType, XmlSchema schema, int depth) {
        if (isBuiltIn(typeName)) {
            return builtIn(typeName);
        }
        if (inlineType != null && isBuiltIn(inlineType.getQName())) {
            return builtIn(inlineType.getQName());
        }
        if (depth > MAX_DERIVATION_DEPTH) {
            return SimpleTypeInfo.STRING;
        }
        if (inlineType instanceof XmlSchemaSimpleType simpleType) {
            return fromSimpleType(simpleType, schema, depth);
        }
        if (typeName == null) {
            return SimpleTypeInfo.STRING;
        }
        XmlSchemaType resolved = ComplexTypeResolver.lookupType(typeName, schema);
        if (resolved instanceof XmlSchemaSimpleType simpleType) {
            return fromSimpleType(simpleType, schema, depth);
        }
        return new SimpleTypeInfo(typeName.getLocalPart(), SchemaFacets.NONE, false);
    }

    private static SimpleTypeInfo fromSimpleType(XmlSchemaSimpleType simpleType, XmlSchema schema, int depth) {
        XmlSchemaSimpleTypeContent content = simpleType.getContent();
        if (content instanceof XmlSchemaSimpleTypeRestriction restriction) {
            SimpleTypeInfo base = resolve(restriction.getBaseTypeName(), restriction.getBaseType(), schema, depth + 1);
            return new SimpleTypeInfo(base.xsdType(), merge(base.facets(), restriction.getFacets()), base.list());
        }
        if (content instanceof XmlSchemaSimpleTypeList list) {
            SimpleTypeInfo item = resolve(list.getItemTypeName(), list.getItemType(), schema, depth + 1);
            return new SimpleTypeInfo(item.xsdType(), item.facets(), true);
        }
        // xs:union: JAXB binds it to String
        return SimpleTypeInfo.STRING;
    }

    private static boolean isBuiltIn(QName typeName) {
        return typeName != null && XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(typeName.getNamespaceURI());
    }

    private static SimpleTypeInfo builtIn(QName typeName) {
        String localName = typeName.getLocalPart();
        return "anySimpleType".equals(localName) || "anyType".equals(localName)
                ? SimpleTypeInfo.STRING
                : new SimpleTypeInfo(localName, SchemaFacets.NONE, false);
    }

    /**
     * Facets of a derivation step override those of its base. Several patterns of one step are alternatives;
     * patterns of different steps would all have to match, keeping only the most derived one is looser but
     * never rejects a valid value.
     */
    static SchemaFacets merge(SchemaFacets base, List<XmlSchemaFacet> facets) {
        List<String> enumeration = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        Integer minLength = base.minLength();
        Integer maxLength = base.maxLength();
        BigDecimal minimum = base.minimum();
        boolean exclusiveMinimum = base.exclusiveMinimum();
        BigDecimal maximum = base.maximum();
        boolean exclusiveMaximum = base.exclusiveMaximum();
        for (XmlSchemaFacet facet : facets) {
            String value = facet.getValue() != null ? facet.getValue().toString().trim() : null;
            if (value == null) {
                continue;
            }
            if (facet instanceof XmlSchemaEnumerationFacet) {
                enumeration.add(value);
            } else if (facet instanceof XmlSchemaPatternFacet) {
                patterns.add(value);
            } else if (facet instanceof XmlSchemaLengthFacet) {
                minLength = integer(value, minLength);
                maxLength = integer(value, maxLength);
            } else if (facet instanceof XmlSchemaMinLengthFacet) {
                minLength = integer(value, minLength);
            } else if (facet instanceof XmlSchemaMaxLengthFacet) {
                maxLength = integer(value, maxLength);
            } else if (facet instanceof XmlSchemaMinInclusiveFacet && decimal(value) != null) {
                minimum = decimal(value);
                exclusiveMinimum = false;
            } else if (facet instanceof XmlSchemaMinExclusiveFacet && decimal(value) != null) {
                minimum = decimal(value);
                exclusiveMinimum = true;
            } else if (facet instanceof XmlSchemaMaxInclusiveFacet && decimal(value) != null) {
                maximum = decimal(value);
                exclusiveMaximum = false;
            } else if (facet instanceof XmlSchemaMaxExclusiveFacet && decimal(value) != null) {
                maximum = decimal(value);
                exclusiveMaximum = true;
            }
        }
        return new SchemaFacets(
                enumeration.isEmpty() ? base.enumeration() : enumeration,
                patterns.isEmpty() ? base.pattern() : alternatives(patterns),
                minLength,
                maxLength,
                minimum,
                exclusiveMinimum,
                maximum,
                exclusiveMaximum
        );
    }

    private static String alternatives(List<String> patterns) {
        if (patterns.size() == 1) {
            return patterns.get(0);
        }
        return String.join("|", patterns.stream().map(pattern -> "(" + pattern + ")").toList());
    }

    private static Integer integer(String value, Integer fallback) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** Range facets of dates and durations are not numbers; they are not carried over. */
    private static BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
