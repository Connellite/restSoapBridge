package io.github.connellite.service.auto.xsd;

import lombok.experimental.UtilityClass;
import org.apache.ws.commons.schema.XmlSchemaAnnotated;
import org.apache.ws.commons.schema.XmlSchemaAnnotationItem;
import org.apache.ws.commons.schema.XmlSchemaDocumentation;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Text of {@code wsdl:documentation} and {@code xs:annotation/xs:documentation}, published as OpenAPI
 * {@code description}: markup dropped, lines trimmed.
 */
@UtilityClass
public class Documentation {

    public static String of(Element documentation) {
        return documentation != null ? normalize(documentation.getTextContent()) : null;
    }

    public static String of(XmlSchemaAnnotated annotated) {
        if (annotated == null || annotated.getAnnotation() == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (XmlSchemaAnnotationItem item : annotated.getAnnotation().getItems()) {
            if (item instanceof XmlSchemaDocumentation documentation && documentation.getMarkup() != null) {
                NodeList markup = documentation.getMarkup();
                for (int index = 0; index < markup.getLength(); index++) {
                    text.append(markup.item(index).getTextContent());
                }
                text.append('\n');
            }
        }
        return normalize(text.toString());
    }

    private static String normalize(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.strip().replaceAll("[ \\t]*\\R[ \\t]*", "\n").replaceAll("\\n{3,}", "\n\n");
        return normalized.isEmpty() ? null : normalized;
    }
}
