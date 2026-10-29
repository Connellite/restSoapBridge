package io.github.connellite.service.auto.xsd;

import lombok.experimental.UtilityClass;
import org.apache.ws.commons.schema.XmlSchemaAnnotated;
import org.apache.ws.commons.schema.XmlSchemaAnnotationItem;
import org.apache.ws.commons.schema.XmlSchemaDocumentation;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Text of {@code wsdl:documentation} and {@code xs:annotation/xs:documentation}, published as OpenAPI
 * {@code description}: markup dropped, the lines of a paragraph joined, paragraphs (and several
 * {@code documentation} elements, e.g. one per language) separated by a blank line.
 */
@UtilityClass
public class Documentation {

    public static String of(Element documentation) {
        return documentation != null ? normalize(documentation.getTextContent()) : null;
    }

    /**
     * Adapted from Membrane API Gateway (Apache License 2.0):
     * <a href="https://github.com/membrane/api-gateway/blob/2a3f9f13809774041cba4e34c3d1efd06b977c20/core/src/main/java/com/predic8/membrane/core/interceptor/wsdl2openapi/XsdDomUtil.java#L131-L141">XsdDomUtil.documentation</a>.
     */
    public static String of(XmlSchemaAnnotated annotated) {
        if (annotated == null || annotated.getAnnotation() == null) {
            return null;
        }
        List<String> paragraphs = new ArrayList<>();
        for (XmlSchemaAnnotationItem item : annotated.getAnnotation().getItems()) {
            if (item instanceof XmlSchemaDocumentation documentation && documentation.getMarkup() != null) {
                StringBuilder text = new StringBuilder();
                NodeList markup = documentation.getMarkup();
                for (int index = 0; index < markup.getLength(); index++) {
                    text.append(markup.item(index).getTextContent());
                }
                String normalized = normalize(text.toString());
                if (normalized != null) {
                    paragraphs.add(normalized);
                }
            }
        }
        return paragraphs.isEmpty() ? null : String.join("\n\n", paragraphs);
    }

    /**
     * Paragraphs of a documentation text: the lines of each joined by a space, paragraphs separated by a blank line.
     * <p>
     * Adapted from Membrane API Gateway (Apache License 2.0):
     * <a href="https://github.com/membrane/api-gateway/blob/2a3f9f13809774041cba4e34c3d1efd06b977c20/core/src/main/java/com/predic8/membrane/core/util/wsdl/parser/WSDLParserUtil.java#L53-L61">WSDLParserUtil.normalizeDocumentation</a>.
     */
    static String normalize(String text) {
        if (text == null) {
            return null;
        }
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : text.strip().split("\\n\\s*\\n")) {
            String joined = paragraph.lines().map(String::strip).filter(line -> !line.isEmpty())
                    .collect(Collectors.joining(" "));
            if (!joined.isEmpty()) {
                paragraphs.add(joined);
            }
        }
        return paragraphs.isEmpty() ? null : String.join("\n\n", paragraphs);
    }
}
