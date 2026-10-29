package io.github.connellite.mapper.xml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.connellite.model.SchemaProperty;
import io.github.connellite.model.SchemaTrees;
import io.github.connellite.model.SchemaType;
import org.apache.cxf.helpers.DOMUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.connellite.service.auto.xsd.ParticleVisitor.MAP_TYPE;

/**
 * Writes a JSON value as the XML element its XSD type describes: elements in schema order (whatever the JSON order),
 * qualified as their form says, attributes and simple content where the type has them, {@code xsi:nil} for a
 * {@code null} sent to a nillable element. JAXB's JSON shapes are accepted as well: a JAXB map as a plain object (or
 * an array of {@code key}/{@code value} pairs), an entry list as a plain object, a single value for an array.
 * <p>
 * A value that cannot be of its type ({@code "abc"} for an {@code xs:int}, an object for a string) is collected as
 * an error with its JSON path; the caller turns the errors into a 400.
 */
public final class JsonToXml {

    private static final String XSI = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;

    private final Map<String, SchemaType> types;
    private final Document document = DOMUtils.createDocument();
    private final Map<String, String> prefixes = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();

    public JsonToXml(Map<String, SchemaType> types) {
        this.types = types;
    }

    public List<String> errors() {
        return errors;
    }

    /**
     * The element {@code name} with the content of {@code value} (an object for a complex type).
     *
     * @param type     type of the element, {@code null} for an element without fields
     * @param ignored  top-level JSON fields that are not content (the SOAP headers object)
     * @param owner    what the fields belong to, for the message about unknown ones
     */
    public Element object(QName name, SchemaType type, JsonNode value, String path, Set<String> ignored, String owner) {
        Element element = element(name.getNamespaceURI(), name.getLocalPart());
        if (value == null || value.isNull()) {
            return finish(element);
        }
        if (!value.isObject()) {
            errors.add(path + ": " + XsdValues.jsonType(value) + " found, object expected");
            return finish(element);
        }
        List<SchemaProperty> properties = type != null ? type.properties() : List.of();
        List<String> unknown = new ArrayList<>();
        for (Iterator<String> names = value.fieldNames(); names.hasNext(); ) {
            String field = names.next();
            if (!ignored.contains(field) && !value.get(field).isNull() && find(properties, field) == null) {
                unknown.add(field);
            }
        }
        if (!unknown.isEmpty()) {
            errors.add("Unknown parameter(s) " + unknown + " for " + owner + "; expected "
                    + properties.stream().map(property -> SchemaTrees.restName(property.name())).toList());
        }
        if (type != null) {
            content(element, type, value, path);
        }
        return finish(element);
    }

    /** A header element of a simple type, or one whose content is described by a single property. */
    public Element value(QName name, SchemaProperty property, JsonNode value, String path) {
        Element element = element(name.getNamespaceURI(), name.getLocalPart());
        if (value.isNull()) {
            return finish(element);
        }
        SchemaType type = typeOf(property);
        if (type != null) {
            content(element, type, value, path);
        } else if (property.isComplex() || "object".equals(property.xsdType())) {
            untyped(element, value);
        } else {
            element.setTextContent(scalar(property, value, path));
        }
        return finish(element);
    }

    private void content(Element element, SchemaType type, JsonNode value, String path) {
        if (!value.isObject()) {
            errors.add(path + ": " + XsdValues.jsonType(value) + " found, object expected");
            return;
        }
        for (SchemaProperty property : type.properties()) {
            String name = SchemaTrees.restName(property.name());
            JsonNode field = value.has(name) ? value.get(name) : value.get(property.name());
            if (field == null) {
                continue;
            }
            String fieldPath = path + "." + name;
            switch (property.kind()) {
                case VALUE -> {
                    if (!field.isNull()) {
                        element.appendChild(document.createTextNode(scalar(property, field, fieldPath)));
                    }
                }
                case ATTRIBUTE -> {
                    if (!field.isNull()) {
                        String namespace = property.namespace() == null || property.namespace().isEmpty()
                                ? null : property.namespace();
                        element.setAttributeNS(namespace, namespace == null
                                ? property.name()
                                : prefix(namespace) + ":" + property.name(), scalar(property, field, fieldPath));
                    }
                }
                case ELEMENT -> element(element, property, field, fieldPath);
            }
        }
    }

    private void element(Element parent, SchemaProperty property, JsonNode value, String path) {
        if (value.isNull()) {
            nil(parent, property);
            return;
        }
        if (MAP_TYPE.equals(property.xsdType())) {
            map(parent, property, value, path);
            return;
        }
        SchemaType type = typeOf(property);
        if (!property.array() || property.list()) {
            single(parent, property, type, value, path);
            return;
        }
        List<JsonNode> items = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(items::add);
        } else if (value.isObject() && isEntryType(type) && !isEntry(value)) {
            // A JAXB entry list sent as a plain object: {"a": "1"} for [{"key": "a", "value": "1"}].
            value.fields().forEachRemaining(entry -> items.add(JsonNodeFactory.instance.objectNode()
                    .put("key", entry.getKey()).set("value", entry.getValue())));
        } else {
            items.add(value);
        }
        for (int index = 0; index < items.size(); index++) {
            JsonNode item = items.get(index);
            if (item.isNull()) {
                nil(parent, property);
            } else {
                single(parent, property, type, item, path + "[" + index + "]");
            }
        }
    }

    private void single(Element parent, SchemaProperty property, SchemaType type, JsonNode value, String path) {
        Element child = child(parent, property);
        if (type != null) {
            content(child, type, value, path);
        } else if (property.isComplex() || "object".equals(property.xsdType())) {
            untyped(child, value);
        } else {
            child.setTextContent(scalar(property, value, path));
        }
    }

    private void nil(Element parent, SchemaProperty property) {
        if (property.nillable()) {
            child(parent, property).setAttributeNS(XSI, prefix(XSI) + ":nil", "true");
        }
    }

    /** A JAXB map: {@code <name><entry><key>k</key><value>v</value></entry>...</name>}. */
    private void map(Element parent, SchemaProperty property, JsonNode value, String path) {
        Map<String, JsonNode> entries = new LinkedHashMap<>();
        if (value.isObject()) {
            value.fields().forEachRemaining(entry -> entries.put(entry.getKey(), entry.getValue()));
        } else if (value.isArray() && allEntries(value)) {
            value.forEach(entry -> entries.put(entry.get("key").asText(), entry.get("value")));
        } else if (value.isArray()) {
            errors.add(path + ": array has no key/value entries, expected an object or [{\"key\": ..., \"value\": ...}]");
            return;
        } else {
            errors.add(path + ": " + XsdValues.jsonType(value) + " cannot be converted to Map");
            return;
        }
        Element map = child(parent, property);
        String namespace = property.mapNamespace() != null ? property.mapNamespace() : "";
        entries.forEach((key, entryValue) -> {
            if (key.isBlank()) {
                // An entry without a key has no place in a map.
                return;
            }
            Element entry = element(namespace, "entry");
            Element keyElement = element(namespace, "key");
            keyElement.setTextContent(key);
            entry.appendChild(keyElement);
            if (entryValue != null && !entryValue.isNull()) {
                Element valueElement = element(namespace, "value");
                valueElement.setTextContent(entryValue.isValueNode() ? entryValue.asText() : entryValue.toString());
                entry.appendChild(valueElement);
            }
            map.appendChild(entry);
        });
    }

    /** Content without a schema (e.g. {@code xs:any}): object fields as child elements, scalars as text. */
    private void untyped(Element element, JsonNode value) {
        if (value.isObject()) {
            value.fields().forEachRemaining(field -> {
                List<JsonNode> items = new ArrayList<>();
                if (field.getValue().isArray()) {
                    field.getValue().forEach(items::add);
                } else {
                    items.add(field.getValue());
                }
                for (JsonNode item : items) {
                    Element child = element(element.getNamespaceURI(), field.getKey());
                    if (!item.isNull()) {
                        untyped(child, item);
                    }
                    element.appendChild(child);
                }
            });
        } else if (!value.isNull()) {
            element.setTextContent(value.isValueNode() ? value.asText() : value.toString());
        }
    }

    private String scalar(SchemaProperty property, JsonNode value, String path) {
        try {
            if (property.list() && value.isArray()) {
                List<String> items = new ArrayList<>();
                value.forEach(item -> items.add(XsdValues.toXml(item, property.xsdType())));
                return String.join(" ", items);
            }
            return XsdValues.toXml(value, property.xsdType());
        } catch (IllegalArgumentException exception) {
            errors.add(path + ": " + exception.getMessage());
            return "";
        }
    }

    private SchemaType typeOf(SchemaProperty property) {
        if (MAP_TYPE.equals(property.xsdType())) {
            return null;
        }
        return property.typeName() != null ? types.get(property.typeName()) : property.inlineType();
    }

    private static boolean isEntryType(SchemaType type) {
        return type != null && type.properties().size() == 2
                && type.properties().stream().map(SchemaProperty::name).collect(Collectors.toSet())
                .equals(Set.of("key", "value"));
    }

    private static boolean isEntry(JsonNode value) {
        return value.has("key") && value.size() <= 2 && (value.size() == 1 || value.has("value"));
    }

    private static boolean allEntries(JsonNode values) {
        for (JsonNode value : values) {
            if (!value.isObject() || !isEntry(value)) {
                return false;
            }
        }
        return true;
    }

    private static SchemaProperty find(List<SchemaProperty> properties, String field) {
        for (SchemaProperty property : properties) {
            if (property.name().equals(field) || SchemaTrees.restName(property.name()).equals(field)) {
                return property;
            }
        }
        return null;
    }

    private Element child(Element parent, SchemaProperty property) {
        Element child = element(property.namespace() != null ? property.namespace() : parent.getNamespaceURI(),
                property.name());
        parent.appendChild(child);
        return child;
    }

    /** A qualified element gets the prefix of its namespace; prefixes are declared on the root element. */
    private Element element(String namespace, String localName) {
        if (namespace == null || namespace.isEmpty()) {
            return document.createElementNS(null, localName);
        }
        return document.createElementNS(namespace, prefix(namespace) + ":" + localName);
    }

    private String prefix(String namespace) {
        return prefixes.computeIfAbsent(namespace, ignored -> XSI.equals(namespace) ? "xsi" : "ns" + (prefixes.size() + 1));
    }

    private Element finish(Element root) {
        prefixes.forEach((namespace, prefix) ->
                root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + prefix, namespace));
        document.appendChild(root);
        return root;
    }
}
