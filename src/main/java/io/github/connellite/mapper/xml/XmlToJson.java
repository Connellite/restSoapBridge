package io.github.connellite.mapper.xml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.connellite.model.SchemaProperty;
import io.github.connellite.model.SchemaTrees;
import io.github.connellite.model.SchemaType;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.connellite.service.auto.xsd.ParticleVisitor.MAP_TYPE;

/**
 * Reads an XML element as the JSON its XSD type describes: properties under their REST names, typed scalars
 * (see {@link XsdValues}), arrays for repeating elements and {@code xs:list}s, {@code null} for {@code xsi:nil},
 * a JAXB map as a plain object. Elements are matched by local name; elements and attributes the type does not
 * know are skipped, absent ones left out.
 */
public final class XmlToJson {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final Map<String, SchemaType> types;

    public XmlToJson(Map<String, SchemaType> types) {
        this.types = types;
    }

    /** The content of an element of complex type. */
    public ObjectNode object(Element element, SchemaType type) {
        ObjectNode json = JSON.objectNode();
        if (type == null) {
            return json;
        }
        for (SchemaProperty property : type.properties()) {
            String name = SchemaTrees.restName(property.name());
            switch (property.kind()) {
                case ATTRIBUTE -> {
                    Attr attribute = attribute(element, property);
                    if (attribute != null) {
                        json.set(name, scalar(property, attribute.getValue()));
                    }
                }
                case VALUE -> {
                    String text = text(element);
                    if (!text.isEmpty() || "string".equals(XsdValues.normalizeType(property.xsdType()))) {
                        json.set(name, scalar(property, text));
                    }
                }
                case ELEMENT -> {
                    List<Element> children = children(element, property.name());
                    if (children.isEmpty()) {
                        continue;
                    }
                    if (property.array() && !property.list()) {
                        ArrayNode items = json.putArray(name);
                        children.forEach(child -> items.add(value(child, property)));
                    } else {
                        json.set(name, value(children.get(0), property));
                    }
                }
            }
        }
        return json;
    }

    /** The JSON of one element that {@code property} describes. */
    public JsonNode value(Element element, SchemaProperty property) {
        if (isNil(element)) {
            return NullNode.getInstance();
        }
        if (MAP_TYPE.equals(property.xsdType())) {
            return map(element);
        }
        SchemaType type = property.typeName() != null ? types.get(property.typeName()) : property.inlineType();
        if (type != null) {
            return object(element, type);
        }
        if (property.isComplex() || "object".equals(property.xsdType())) {
            return untyped(element);
        }
        return scalar(property, text(element));
    }

    private JsonNode scalar(SchemaProperty property, String lexical) {
        if (!property.list()) {
            return XsdValues.toJson(lexical, property.xsdType());
        }
        ArrayNode items = JSON.arrayNode();
        for (String item : lexical.trim().split("\\s+")) {
            if (!item.isEmpty()) {
                items.add(XsdValues.toJson(item, property.xsdType()));
            }
        }
        return items;
    }

    /** {@code <entry><key>k</key><value>v</value></entry>...} as {@code {"k": "v"}}. */
    private static ObjectNode map(Element element) {
        ObjectNode map = JSON.objectNode();
        for (Element entry : children(element, "entry")) {
            List<Element> key = children(entry, "key");
            List<Element> value = children(entry, "value");
            if (!key.isEmpty()) {
                map.set(text(key.get(0)), value.isEmpty() || isNil(value.get(0))
                        ? NullNode.getInstance()
                        : TextNode.valueOf(text(value.get(0))));
            }
        }
        return map;
    }

    /** Content without a schema: child elements as properties (repeated ones as arrays), text otherwise. */
    private static JsonNode untyped(Element element) {
        List<Element> children = children(element, null);
        if (children.isEmpty()) {
            String text = text(element);
            return text.isEmpty() ? JSON.objectNode() : TextNode.valueOf(text);
        }
        Map<String, List<JsonNode>> fields = new LinkedHashMap<>();
        for (Element child : children) {
            fields.computeIfAbsent(child.getLocalName(), ignored -> new ArrayList<>())
                    .add(isNil(child) ? NullNode.getInstance() : untyped(child));
        }
        ObjectNode json = JSON.objectNode();
        fields.forEach((name, values) -> {
            if (values.size() == 1) {
                json.set(name, values.get(0));
            } else {
                values.forEach(json.putArray(name)::add);
            }
        });
        return json;
    }

    private static Attr attribute(Element element, SchemaProperty property) {
        String namespace = property.namespace() == null || property.namespace().isEmpty() ? null : property.namespace();
        Attr attribute = element.getAttributeNodeNS(namespace, property.name());
        return attribute != null ? attribute : element.getAttributeNode(property.name());
    }

    /** Child elements of a local name ({@code null}: all). */
    private static List<Element> children(Element element, String localName) {
        List<Element> children = new ArrayList<>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && (localName == null || localName.equals(localName(child)))) {
                children.add(child);
            }
        }
        return children;
    }

    private static String localName(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getTagName();
    }

    /** The element's own text, without that of child elements. */
    private static String text(Element element) {
        StringBuilder text = new StringBuilder();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
        }
        return text.toString();
    }

    private static boolean isNil(Element element) {
        String nil = element.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil");
        return "true".equals(nil.trim()) || "1".equals(nil.trim());
    }
}
