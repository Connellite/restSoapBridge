package io.github.connellite.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * XSD restrictions of a simple-typed field ({@code xs:enumeration}, {@code xs:pattern}, length and range facets)
 * that survive into the OpenAPI schema and request validation. {@code pattern} is the XSD regular expression as
 * declared (implicitly anchored); bounds are inclusive unless the matching {@code exclusive*} flag is set.
 */
public record SchemaFacets(
        List<String> enumeration,
        String pattern,
        Integer minLength,
        Integer maxLength,
        BigDecimal minimum,
        boolean exclusiveMinimum,
        BigDecimal maximum,
        boolean exclusiveMaximum
) {

    public static final SchemaFacets NONE = new SchemaFacets(List.of(), null, null, null, null, false, null, false);

    public SchemaFacets {
        enumeration = enumeration == null ? List.of() : List.copyOf(enumeration);
    }

    public boolean isEmpty() {
        return equals(NONE);
    }
}
