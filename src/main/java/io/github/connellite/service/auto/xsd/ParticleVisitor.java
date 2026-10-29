package io.github.connellite.service.auto.xsd;

import io.github.connellite.model.SchemaField;
import io.github.connellite.model.SchemaField.ChoiceRef;
import io.github.connellite.service.auto.xsd.SimpleTypeResolver.SimpleTypeInfo;
import lombok.experimental.UtilityClass;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaAll;
import org.apache.ws.commons.schema.XmlSchemaAllMember;
import org.apache.ws.commons.schema.XmlSchemaAny;
import org.apache.ws.commons.schema.XmlSchemaAttribute;
import org.apache.ws.commons.schema.XmlSchemaAttributeGroup;
import org.apache.ws.commons.schema.XmlSchemaAttributeGroupRef;
import org.apache.ws.commons.schema.XmlSchemaChoice;
import org.apache.ws.commons.schema.XmlSchemaChoiceMember;
import org.apache.ws.commons.schema.XmlSchemaComplexContentExtension;
import org.apache.ws.commons.schema.XmlSchemaComplexContentRestriction;
import org.apache.ws.commons.schema.XmlSchemaComplexType;
import org.apache.ws.commons.schema.XmlSchemaContent;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.apache.ws.commons.schema.XmlSchemaGroup;
import org.apache.ws.commons.schema.XmlSchemaGroupRef;
import org.apache.ws.commons.schema.XmlSchemaParticle;
import org.apache.ws.commons.schema.XmlSchemaSequence;
import org.apache.ws.commons.schema.XmlSchemaSequenceMember;
import org.apache.ws.commons.schema.XmlSchemaSimpleContentExtension;
import org.apache.ws.commons.schema.XmlSchemaSimpleContentRestriction;
import org.apache.ws.commons.schema.XmlSchemaType;
import org.apache.ws.commons.schema.XmlSchemaUse;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@UtilityClass
public class ParticleVisitor {

    /** Pseudo XSD type of a collapsed JAXB {@code java.util.Map} (entry/key/value): a JSON object of strings. */
    public static final String MAP_TYPE = "map";

    /** JSON name of the text of a simple-content type: JAXB binds it to an {@code @XmlValue value} property. */
    static final String SIMPLE_CONTENT_VALUE = "value";

    public static List<SchemaField> fieldPaths(XmlSchemaComplexType complexType, XmlSchema schema) {
        List<SchemaField> fields = new ArrayList<>();
        collectTypeContent(complexType, schema, "", true, fields, new LinkedHashSet<>());
        return fields;
    }

    /**
     * Elements and attributes of a complex type. For a derived type the base type content comes first,
     * as in the JAXB class hierarchy; attributes follow the elements of the same derivation step.
     */
    private static void collectTypeContent(
            XmlSchemaComplexType complexType,
            XmlSchema schema,
            String prefix,
            boolean parentRequired,
            List<SchemaField> fields,
            Set<String> visiting
    ) {
        XmlSchemaContent content = complexType.getContentModel() != null
                ? complexType.getContentModel().getContent()
                : null;
        if (content instanceof XmlSchemaComplexContentExtension extension) {
            collectBaseTypeContent(extension.getBaseTypeName(), schema, prefix, parentRequired, fields, visiting);
            collectFieldPaths(extension.getParticle(), schema, prefix, parentRequired, fields, visiting);
            collectAttributes(extension.getAttributes(), schema, prefix, parentRequired, fields);
        } else if (content instanceof XmlSchemaComplexContentRestriction restriction) {
            // A restriction restates the content it keeps.
            collectFieldPaths(restriction.getParticle(), schema, prefix, parentRequired, fields, visiting);
            collectAttributes(restriction.getAttributes(), schema, prefix, parentRequired, fields);
        } else if (content instanceof XmlSchemaSimpleContentExtension extension) {
            collectSimpleContent(extension.getBaseTypeName(), schema, prefix, parentRequired, fields, visiting);
            collectAttributes(extension.getAttributes(), schema, prefix, parentRequired, fields);
        } else if (content instanceof XmlSchemaSimpleContentRestriction restriction) {
            collectSimpleContent(restriction.getBaseTypeName(), schema, prefix, parentRequired, fields, visiting);
            collectAttributes(restriction.getAttributes(), schema, prefix, parentRequired, fields);
        } else {
            collectFieldPaths(complexType.getParticle(), schema, prefix, parentRequired, fields, visiting);
            collectAttributes(complexType.getAttributes(), schema, prefix, parentRequired, fields);
        }
    }

    private static void collectBaseTypeContent(
            QName baseTypeName,
            XmlSchema schema,
            String prefix,
            boolean parentRequired,
            List<SchemaField> fields,
            Set<String> visiting
    ) {
        if (baseTypeName == null || XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(baseTypeName.getNamespaceURI())) {
            return;
        }
        if (!(ComplexTypeResolver.lookupType(baseTypeName, schema) instanceof XmlSchemaComplexType baseType)) {
            return;
        }
        String typeKey = "base:" + baseTypeName;
        if (!visiting.add(typeKey)) {
            return;
        }
        collectTypeContent(baseType, schema, prefix, parentRequired, fields, visiting);
        visiting.remove(typeKey);
    }

    /**
     * Simple content: the text value plus attributes. A complex base type (simple content derived from
     * another one) contributes its value and attributes; a simple base type only types the value.
     */
    private static void collectSimpleContent(
            QName baseTypeName,
            XmlSchema schema,
            String prefix,
            boolean parentRequired,
            List<SchemaField> fields,
            Set<String> visiting
    ) {
        XmlSchemaType baseType = ComplexTypeResolver.lookupType(baseTypeName, schema);
        if (baseType instanceof XmlSchemaComplexType) {
            collectBaseTypeContent(baseTypeName, schema, prefix, parentRequired, fields, visiting);
            return;
        }
        SimpleTypeInfo value = SimpleTypeResolver.resolve(baseTypeName, null, schema);
        addField(fields, simpleField(prefix + SIMPLE_CONTENT_VALUE, value, parentRequired));
    }

    private static void collectAttributes(
            List<?> attributes,
            XmlSchema schema,
            String prefix,
            boolean parentRequired,
            List<SchemaField> fields
    ) {
        collectAttributes(attributes, schema, prefix, parentRequired, fields, new LinkedHashSet<>());
    }

    private static void collectAttributes(
            List<?> attributes,
            XmlSchema schema,
            String prefix,
            boolean parentRequired,
            List<SchemaField> fields,
            Set<QName> visitingGroups
    ) {
        for (Object item : attributes) {
            if (item instanceof XmlSchemaAttribute attribute) {
                collectAttribute(attribute, schema, prefix, parentRequired, fields);
            } else if (item instanceof XmlSchemaAttributeGroupRef groupRef) {
                XmlSchemaAttributeGroup group = groupRef.getRef().getTarget();
                if (group != null && visitingGroups.add(groupRef.getRef().getTargetQName())) {
                    collectAttributes(group.getAttributes(), schema, prefix, parentRequired, fields, visitingGroups);
                }
            } else if (item instanceof XmlSchemaAttributeGroup group) {
                collectAttributes(group.getAttributes(), schema, prefix, parentRequired, fields, visitingGroups);
            }
        }
    }

    private static void collectAttribute(
            XmlSchemaAttribute attribute,
            XmlSchema schema,
            String prefix,
            boolean parentRequired,
            List<SchemaField> fields
    ) {
        if (attribute.getUse() == XmlSchemaUse.PROHIBITED) {
            return;
        }
        XmlSchemaAttribute resolved = attribute.isRef() && attribute.getRef().getTarget() != null
                ? attribute.getRef().getTarget()
                : attribute;
        String name = attribute.getName() != null ? attribute.getName() : resolved.getName();
        if (name == null) {
            return;
        }
        SimpleTypeInfo type = SimpleTypeResolver.resolve(resolved.getSchemaTypeName(), resolved.getSchemaType(), schema);
        boolean required = parentRequired && attribute.getUse() == XmlSchemaUse.REQUIRED;
        String documentation = firstText(Documentation.of(attribute), Documentation.of(resolved));
        addField(fields, simpleField(prefix + name, type, required, documentation));
    }

    private static SchemaField simpleField(String path, SimpleTypeInfo type, boolean required) {
        return simpleField(path, type, required, null);
    }

    private static SchemaField simpleField(String path, SimpleTypeInfo type, boolean required, String documentation) {
        return new SchemaField(type.list() ? path + "[]" : path, type.xsdType(), required, type.list(), type.facets(),
                false, null, null, documentation, List.of());
    }

    /** An attribute restated by a derived type, or one named like an element, is listed once. */
    private static void addField(List<SchemaField> fields, SchemaField field) {
        if (fields.stream().noneMatch(existing -> existing.path().equals(field.path()))) {
            fields.add(field);
        }
    }

    private static void collectFieldPaths(
            XmlSchemaParticle particle,
            XmlSchema schema,
            String prefix,
            boolean parentRequired,
            List<SchemaField> fields,
            Set<String> visiting
    ) {
        if (particle == null) {
            return;
        }
        if (particle instanceof XmlSchemaElement element && element.getName() != null) {
            collectElementField(element, schema, prefix, parentRequired, fields, visiting);
            return;
        }
        boolean required = parentRequired && particle.getMinOccurs() > 0;
        if (particle instanceof XmlSchemaSequence sequence) {
            for (XmlSchemaSequenceMember item : sequence.getItems()) {
                collectSequenceMember(item, schema, prefix, required, fields, visiting);
            }
        }
        if (particle instanceof XmlSchemaChoice choice) {
            // A repeating choice is one JAXB list of mixed items: no "one of" constraint fits it.
            boolean repeating = choice.getMaxOccurs() > 1;
            boolean optional = !required || choice.getItems().stream().anyMatch(ParticleVisitor::emptyable);
            String group = prefix + "#choice" + fields.size();
            int alternative = 0;
            for (XmlSchemaChoiceMember item : choice.getItems()) {
                int start = fields.size();
                collectChoiceMember(item, schema, prefix, false, fields, visiting);
                if (!repeating) {
                    ChoiceRef ref = new ChoiceRef(group, alternative, depth(prefix), optional);
                    for (int index = start; index < fields.size(); index++) {
                        fields.set(index, fields.get(index).withChoice(ref));
                    }
                }
                alternative++;
            }
        }
        if (particle instanceof XmlSchemaAll all) {
            for (XmlSchemaAllMember item : all.getItems()) {
                collectAllMember(item, schema, prefix, required, fields, visiting);
            }
        }
        if (particle instanceof XmlSchemaGroupRef groupRef) {
            collectFieldPaths(groupParticle(groupRef, schema), schema, prefix, required, fields, visiting);
        }
    }

    /** Number of path segments of a field prefix ({@code a.b[].} → 2). */
    private static int depth(String prefix) {
        return prefix.isEmpty() ? 0 : prefix.split("\\.").length;
    }

    /** Whether a choice alternative may hold no element at all, which leaves the choice itself optional. */
    private static boolean emptyable(Object member) {
        if (member instanceof XmlSchemaParticle particle && particle.getMinOccurs() == 0) {
            return true;
        }
        if (member instanceof XmlSchemaSequence sequence) {
            return sequence.getItems().stream().allMatch(ParticleVisitor::emptyable);
        }
        if (member instanceof XmlSchemaAll all) {
            return all.getItems().stream().allMatch(ParticleVisitor::emptyable);
        }
        if (member instanceof XmlSchemaChoice choice) {
            return choice.getItems().stream().anyMatch(ParticleVisitor::emptyable);
        }
        if (member instanceof XmlSchemaGroupRef groupRef && groupRef.getParticle() != null) {
            return emptyable(groupRef.getParticle());
        }
        return member instanceof XmlSchemaAny;
    }

    private static XmlSchemaParticle groupParticle(XmlSchemaGroupRef groupRef, XmlSchema schema) {
        if (groupRef.getParticle() != null) {
            return groupRef.getParticle();
        }
        XmlSchemaGroup group = groupRef.getRefName() != null && schema.getParent() != null
                ? schema.getParent().getGroupByQName(groupRef.getRefName())
                : null;
        return group != null ? group.getParticle() : null;
    }

    private static void collectSequenceMember(
            XmlSchemaSequenceMember item,
            XmlSchema schema,
            String prefix,
            boolean parentRequired,
            List<SchemaField> fields,
            Set<String> visiting
    ) {
        if (item instanceof XmlSchemaElement child) {
            collectElementField(child, schema, prefix, parentRequired, fields, visiting);
        } else if (item instanceof XmlSchemaParticle particle) {
            collectFieldPaths(particle, schema, prefix, parentRequired, fields, visiting);
        }
    }

    private static void collectChoiceMember(
            XmlSchemaChoiceMember item,
            XmlSchema schema,
            String prefix,
            boolean parentRequired,
            List<SchemaField> fields,
            Set<String> visiting
    ) {
        if (item instanceof XmlSchemaElement child) {
            collectElementField(child, schema, prefix, parentRequired, fields, visiting);
        } else if (item instanceof XmlSchemaParticle particle) {
            collectFieldPaths(particle, schema, prefix, parentRequired, fields, visiting);
        }
    }

    private static void collectAllMember(
            XmlSchemaAllMember item,
            XmlSchema schema,
            String prefix,
            boolean parentRequired,
            List<SchemaField> fields,
            Set<String> visiting
    ) {
        if (item instanceof XmlSchemaElement child) {
            collectElementField(child, schema, prefix, parentRequired, fields, visiting);
        } else if (item instanceof XmlSchemaParticle particle) {
            collectFieldPaths(particle, schema, prefix, parentRequired, fields, visiting);
        }
    }

    private static void collectElementField(
            XmlSchemaElement element,
            XmlSchema schema,
            String prefix,
            boolean parentRequired,
            List<SchemaField> fields,
            Set<String> visiting
    ) {
        XmlSchemaElement resolvedElement = ComplexTypeResolver.resolveElementReference(element);
        String name = element.getName() != null ? element.getName() : resolvedElement.getName();
        if (name == null) {
            return;
        }

        String path = prefix.isEmpty() ? name : prefix + name;
        boolean collection = isRepeating(element);
        boolean required = parentRequired && element.getMinOccurs() > 0;
        boolean nillable = element.isNillable() || resolvedElement.isNillable();
        Integer minItems = collection && element.getMinOccurs() > 0 ? (int) element.getMinOccurs() : null;
        Integer maxItems = collection && element.getMaxOccurs() < Integer.MAX_VALUE ? (int) element.getMaxOccurs() : null;
        String documentation = firstText(Documentation.of(element), Documentation.of(resolvedElement));
        XmlSchemaComplexType complexType = ComplexTypeResolver.resolve(resolvedElement, schema);

        if (complexType != null) {
            documentation = firstText(documentation, Documentation.of(complexType));
            // JAXB Map<K,V> in XSD is sequence of repeating entry{key,value}.
            // Java beans expose a real Map (no getEntry()), so expand entry[] into SpEL would 502.
            if (!collection && isJaxbMapComplexType(complexType, schema)) {
                fields.add(new SchemaField(path, MAP_TYPE, required, false, null, nillable, null, null,
                        documentation, List.of()));
                return;
            }

            // By QName: a:Item holding a b:Item is no recursion.
            String typeKey = complexType.getQName() != null ? complexType.getQName().toString() : path;
            if (!visiting.add(typeKey)) {
                fields.add(new SchemaField(collection ? path + "[]" : path, "object", required, collection, null,
                        nillable, minItems, maxItems, documentation, List.of()));
                return;
            }

            List<SchemaField> nestedFields = new ArrayList<>();
            String nestedPrefix = collection ? path + "[]." : path + ".";
            collectTypeContent(complexType, schema, nestedPrefix, required, nestedFields, visiting);
            visiting.remove(typeKey);

            if (collection) {
                fields.add(new SchemaField(path + "[]", "object", required, true, null, nillable, minItems, maxItems,
                        documentation, List.of()));
            }
            if (!nestedFields.isEmpty()) {
                fields.addAll(nestedFields);
            } else if (!collection) {
                fields.add(new SchemaField(path, "object", required, false, null, nillable, null, null,
                        documentation, List.of()));
            }
            return;
        }

        SimpleTypeInfo type = SimpleTypeResolver.resolve(
                resolvedElement.getSchemaTypeName(), resolvedElement.getSchemaType(), schema);
        boolean list = collection || type.list();
        fields.add(new SchemaField(list ? path + "[]" : path, type.xsdType(), required, list, type.facets(),
                nillable, minItems, maxItems, documentation, List.of()));
    }

    private static String firstText(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isRepeating(XmlSchemaElement element) {
        long maxOccurs = element.getMaxOccurs();
        return maxOccurs > 1;
    }

    /**
     * Detects the JAXB/CXF representation of {@code Map} in XML Schema:
     * a complex type whose only particle is a repeating {@code entry} with {@code key}/{@code value}.
     */
    private boolean isJaxbMapComplexType(XmlSchemaComplexType complexType, XmlSchema schema) {
        XmlSchemaParticle particle = complexType.getParticle();
        if (!(particle instanceof XmlSchemaSequence sequence) || sequence.getItems().size() != 1) {
            return false;
        }
        XmlSchemaSequenceMember only = sequence.getItems().get(0);
        if (!(only instanceof XmlSchemaElement entryElement)) {
            return false;
        }
        XmlSchemaElement resolvedEntry = ComplexTypeResolver.resolveElementReference(entryElement);
        String entryName = entryElement.getName() != null ? entryElement.getName() : resolvedEntry.getName();
        if (!"entry".equals(entryName) || !isRepeating(entryElement)) {
            return false;
        }
        XmlSchemaComplexType entryType = ComplexTypeResolver.resolve(resolvedEntry, schema);
        if (entryType == null) {
            return false;
        }
        boolean hasKey = false;
        boolean hasValue = false;
        List<XmlSchemaElement> children = sequenceElements(entryType.getParticle());
        if (children.isEmpty()) {
            return false;
        }
        for (XmlSchemaElement child : children) {
            String childName = child.getName();
            if (childName == null) {
                XmlSchemaElement resolved = ComplexTypeResolver.resolveElementReference(child);
                childName = resolved.getName();
            }
            if ("key".equals(childName)) {
                hasKey = true;
            } else if ("value".equals(childName)) {
                hasValue = true;
            } else {
                return false;
            }
        }
        return hasKey && hasValue;
    }

    private List<XmlSchemaElement> sequenceElements(XmlSchemaParticle particle) {
        List<XmlSchemaElement> elements = new ArrayList<>();
        if (particle instanceof XmlSchemaSequence sequence) {
            for (XmlSchemaSequenceMember item : sequence.getItems()) {
                if (item instanceof XmlSchemaElement element) {
                    elements.add(element);
                }
            }
        } else if (particle instanceof XmlSchemaAll all) {
            for (XmlSchemaAllMember item : all.getItems()) {
                if (item instanceof XmlSchemaElement element) {
                    elements.add(element);
                }
            }
        }
        return elements;
    }
}
