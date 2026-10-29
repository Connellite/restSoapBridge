package io.github.connellite.service.auto.xsd;

import io.github.connellite.model.SchemaField;
import io.github.connellite.model.SchemaProperty;
import io.github.connellite.model.SchemaTrees;
import io.github.connellite.model.SchemaType;
import io.github.connellite.service.auto.xsd.SimpleTypeResolver.SimpleTypeInfo;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@link SchemaType} of XSD complex types in the shape JAXB binds them to. Named types go into a registry
 * shared by all types of a WSDL, each built once; properties refer to them by name, so a type may contain itself.
 */
public class ParticleVisitor {

    /** Pseudo XSD type of a collapsed JAXB {@code java.util.Map} (entry/key/value): a JSON object of strings. */
    public static final String MAP_TYPE = "map";

    /** JSON name of the text of a simple-content type: JAXB binds it to an {@code @XmlValue value} property. */
    static final String SIMPLE_CONTENT_VALUE = "value";

    private final Map<String, SchemaType> types;
    private final Set<String> building = new HashSet<>();
    private final Set<XmlSchemaComplexType> anonymousBuilding = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * @param types registry of named types, by QName; filled as types are reached
     */
    public ParticleVisitor(Map<String, SchemaType> types) {
        this.types = types;
    }

    /** The fields of a complex type as paths, named types expanded in place. */
    public static List<SchemaField> fieldPaths(XmlSchemaComplexType complexType, XmlSchema schema) {
        Map<String, SchemaType> types = new LinkedHashMap<>();
        return SchemaTrees.flatten(new ParticleVisitor(types).type(complexType, schema), types);
    }

    /** The type of a complex type; a named one is the registry entry. */
    public SchemaType type(XmlSchemaComplexType complexType, XmlSchema schema) {
        if (complexType.getQName() == null) {
            return new SchemaType(null, content(complexType, schema), Documentation.of(complexType));
        }
        return types.get(reference(complexType, schema));
    }

    /**
     * Registry key of a named type, built on first use. A type reached again while it is being built (it contains
     * itself) is only referred to.
     */
    private String reference(XmlSchemaComplexType complexType, XmlSchema schema) {
        String key = complexType.getQName().toString();
        if (!types.containsKey(key) && building.add(key)) {
            try {
                types.put(key, new SchemaType(key, content(complexType, schema), Documentation.of(complexType)));
            } finally {
                building.remove(key);
            }
        }
        return key;
    }

    private List<SchemaProperty> content(XmlSchemaComplexType complexType, XmlSchema schema) {
        List<SchemaProperty> properties = new ArrayList<>();
        collectTypeContent(complexType, schema, true, properties, new LinkedHashSet<>());
        return properties;
    }

    /**
     * Elements and attributes of a complex type. For a derived type the base type content comes first,
     * as in the JAXB class hierarchy; attributes follow the elements of the same derivation step.
     *
     * @param required whether the content is required within the type (an optional particle around it clears it)
     */
    private void collectTypeContent(
            XmlSchemaComplexType complexType,
            XmlSchema schema,
            boolean required,
            List<SchemaProperty> properties,
            Set<String> visitingBases
    ) {
        XmlSchemaContent content = complexType.getContentModel() != null
                ? complexType.getContentModel().getContent()
                : null;
        if (content instanceof XmlSchemaComplexContentExtension extension) {
            collectBaseTypeContent(extension.getBaseTypeName(), schema, required, properties, visitingBases);
            collectParticle(extension.getParticle(), schema, required, properties, visitingBases);
            collectAttributes(extension.getAttributes(), schema, required, properties, new LinkedHashSet<>());
        } else if (content instanceof XmlSchemaComplexContentRestriction restriction) {
            // A restriction restates the content it keeps.
            collectParticle(restriction.getParticle(), schema, required, properties, visitingBases);
            collectAttributes(restriction.getAttributes(), schema, required, properties, new LinkedHashSet<>());
        } else if (content instanceof XmlSchemaSimpleContentExtension extension) {
            collectSimpleContent(extension.getBaseTypeName(), schema, required, properties, visitingBases);
            collectAttributes(extension.getAttributes(), schema, required, properties, new LinkedHashSet<>());
        } else if (content instanceof XmlSchemaSimpleContentRestriction restriction) {
            collectSimpleContent(restriction.getBaseTypeName(), schema, required, properties, visitingBases);
            collectAttributes(restriction.getAttributes(), schema, required, properties, new LinkedHashSet<>());
        } else {
            collectParticle(complexType.getParticle(), schema, required, properties, visitingBases);
            collectAttributes(complexType.getAttributes(), schema, required, properties, new LinkedHashSet<>());
        }
    }

    private void collectBaseTypeContent(
            QName baseTypeName,
            XmlSchema schema,
            boolean required,
            List<SchemaProperty> properties,
            Set<String> visitingBases
    ) {
        if (baseTypeName == null || XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(baseTypeName.getNamespaceURI())) {
            return;
        }
        if (!(ComplexTypeResolver.lookupType(baseTypeName, schema) instanceof XmlSchemaComplexType baseType)) {
            return;
        }
        if (!visitingBases.add(baseTypeName.toString())) {
            return;
        }
        collectTypeContent(baseType, schema, required, properties, visitingBases);
        visitingBases.remove(baseTypeName.toString());
    }

    /**
     * Simple content: the text value plus attributes. A complex base type (simple content derived from
     * another one) contributes its value and attributes; a simple base type only types the value.
     */
    private void collectSimpleContent(
            QName baseTypeName,
            XmlSchema schema,
            boolean required,
            List<SchemaProperty> properties,
            Set<String> visitingBases
    ) {
        XmlSchemaType baseType = ComplexTypeResolver.lookupType(baseTypeName, schema);
        if (baseType instanceof XmlSchemaComplexType) {
            collectBaseTypeContent(baseTypeName, schema, required, properties, visitingBases);
            return;
        }
        SimpleTypeInfo value = SimpleTypeResolver.resolve(baseTypeName, null, schema);
        addProperty(properties, simpleProperty(SIMPLE_CONTENT_VALUE, value, required, null));
    }

    private void collectAttributes(
            List<?> attributes,
            XmlSchema schema,
            boolean required,
            List<SchemaProperty> properties,
            Set<QName> visitingGroups
    ) {
        for (Object item : attributes) {
            if (item instanceof XmlSchemaAttribute attribute) {
                collectAttribute(attribute, schema, required, properties);
            } else if (item instanceof XmlSchemaAttributeGroupRef groupRef) {
                XmlSchemaAttributeGroup group = groupRef.getRef().getTarget();
                if (group != null && visitingGroups.add(groupRef.getRef().getTargetQName())) {
                    collectAttributes(group.getAttributes(), schema, required, properties, visitingGroups);
                }
            } else if (item instanceof XmlSchemaAttributeGroup group) {
                collectAttributes(group.getAttributes(), schema, required, properties, visitingGroups);
            }
        }
    }

    private void collectAttribute(
            XmlSchemaAttribute attribute,
            XmlSchema schema,
            boolean parentRequired,
            List<SchemaProperty> properties
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
        addProperty(properties, simpleProperty(name, type, required, documentation));
    }

    private static SchemaProperty simpleProperty(String name, SimpleTypeInfo type, boolean required,
                                                 String documentation) {
        return new SchemaProperty(name, type.xsdType(), type.facets(), null, null, required, type.list(), false,
                null, null, documentation, List.of());
    }

    /** An attribute restated by a derived type, or one named like an element, is listed once. */
    private static void addProperty(List<SchemaProperty> properties, SchemaProperty property) {
        if (properties.stream().noneMatch(existing -> existing.name().equals(property.name()))) {
            properties.add(property);
        }
    }

    private void collectParticle(
            XmlSchemaParticle particle,
            XmlSchema schema,
            boolean parentRequired,
            List<SchemaProperty> properties,
            Set<String> visitingBases
    ) {
        if (particle == null) {
            return;
        }
        if (particle instanceof XmlSchemaElement element && element.getName() != null) {
            collectElement(element, schema, parentRequired, properties);
            return;
        }
        boolean required = parentRequired && particle.getMinOccurs() > 0;
        if (particle instanceof XmlSchemaSequence sequence) {
            for (XmlSchemaSequenceMember item : sequence.getItems()) {
                collectMember(item, schema, required, properties, visitingBases);
            }
        }
        if (particle instanceof XmlSchemaChoice choice) {
            // A repeating choice is one JAXB list of mixed items: no "one of" constraint fits it.
            boolean repeating = choice.getMaxOccurs() > 1;
            boolean optional = !required || choice.getItems().stream().anyMatch(ParticleVisitor::emptyable);
            String group = "#choice" + properties.size();
            int alternative = 0;
            for (XmlSchemaChoiceMember item : choice.getItems()) {
                int start = properties.size();
                collectMember(item, schema, false, properties, visitingBases);
                if (!repeating) {
                    SchemaProperty.Choice member = new SchemaProperty.Choice(group, alternative, optional);
                    for (int index = start; index < properties.size(); index++) {
                        properties.set(index, properties.get(index).withOuterChoice(member));
                    }
                }
                alternative++;
            }
        }
        if (particle instanceof XmlSchemaAll all) {
            for (XmlSchemaAllMember item : all.getItems()) {
                collectMember(item, schema, required, properties, visitingBases);
            }
        }
        if (particle instanceof XmlSchemaGroupRef groupRef) {
            collectParticle(groupParticle(groupRef, schema), schema, required, properties, visitingBases);
        }
    }

    /** A member of a sequence, choice or all: an element or a nested particle. */
    private void collectMember(
            Object item,
            XmlSchema schema,
            boolean required,
            List<SchemaProperty> properties,
            Set<String> visitingBases
    ) {
        if (item instanceof XmlSchemaElement child) {
            collectElement(child, schema, required, properties);
        } else if (item instanceof XmlSchemaParticle particle) {
            collectParticle(particle, schema, required, properties, visitingBases);
        }
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

    private void collectElement(
            XmlSchemaElement element,
            XmlSchema schema,
            boolean parentRequired,
            List<SchemaProperty> properties
    ) {
        XmlSchemaElement resolvedElement = ComplexTypeResolver.resolveElementReference(element);
        String name = element.getName() != null ? element.getName() : resolvedElement.getName();
        if (name == null) {
            return;
        }

        boolean collection = isRepeating(element);
        boolean required = parentRequired && element.getMinOccurs() > 0;
        boolean nillable = element.isNillable() || resolvedElement.isNillable();
        Integer minItems = collection && element.getMinOccurs() > 0 ? (int) element.getMinOccurs() : null;
        Integer maxItems = collection && element.getMaxOccurs() < Integer.MAX_VALUE ? (int) element.getMaxOccurs() : null;
        String documentation = firstText(Documentation.of(element), Documentation.of(resolvedElement));
        XmlSchemaComplexType complexType = ComplexTypeResolver.resolve(resolvedElement, schema);

        if (complexType != null) {
            // JAXB Map<K,V> in XSD is sequence of repeating entry{key,value}.
            // Java beans expose a real Map (no getEntry()), so expand entry[] into SpEL would 502.
            if (!collection && isJaxbMapComplexType(complexType, schema)) {
                properties.add(new SchemaProperty(name, MAP_TYPE, null, null, null, required, false, nillable,
                        null, null, firstText(documentation, Documentation.of(complexType)), List.of()));
                return;
            }
            if (complexType.getQName() != null) {
                properties.add(new SchemaProperty(name, "object", null, reference(complexType, schema), null,
                        required, collection, nillable, minItems, maxItems, documentation, List.of()));
                return;
            }
            // An anonymous type can only contain itself through an element reference; stop there.
            SchemaType inline = null;
            if (anonymousBuilding.add(complexType)) {
                try {
                    inline = new SchemaType(null, content(complexType, schema), Documentation.of(complexType));
                } finally {
                    anonymousBuilding.remove(complexType);
                }
            }
            properties.add(new SchemaProperty(name, "object", null, null, inline, required, collection, nillable,
                    minItems, maxItems, inline != null ? documentation
                    : firstText(documentation, Documentation.of(complexType)), List.of()));
            return;
        }

        SimpleTypeInfo type = SimpleTypeResolver.resolve(
                resolvedElement.getSchemaTypeName(), resolvedElement.getSchemaType(), schema);
        properties.add(new SchemaProperty(name, type.xsdType(), type.facets(), null, null, required,
                collection || type.list(), nillable, minItems, maxItems, documentation, List.of()));
    }

    private static String firstText(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isRepeating(XmlSchemaElement element) {
        return element.getMaxOccurs() > 1;
    }

    /**
     * Detects the JAXB/CXF representation of {@code Map} in XML Schema:
     * a complex type whose only particle is a repeating {@code entry} with {@code key}/{@code value}.
     */
    private static boolean isJaxbMapComplexType(XmlSchemaComplexType complexType, XmlSchema schema) {
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

    private static List<XmlSchemaElement> sequenceElements(XmlSchemaParticle particle) {
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
