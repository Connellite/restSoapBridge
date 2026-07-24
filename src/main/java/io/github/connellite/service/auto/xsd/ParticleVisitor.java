package io.github.connellite.service.auto.xsd;

import io.github.connellite.model.SchemaField;
import lombok.experimental.UtilityClass;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaAll;
import org.apache.ws.commons.schema.XmlSchemaAllMember;
import org.apache.ws.commons.schema.XmlSchemaChoice;
import org.apache.ws.commons.schema.XmlSchemaChoiceMember;
import org.apache.ws.commons.schema.XmlSchemaComplexType;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.apache.ws.commons.schema.XmlSchemaParticle;
import org.apache.ws.commons.schema.XmlSchemaSequence;
import org.apache.ws.commons.schema.XmlSchemaSequenceMember;
import org.apache.ws.commons.schema.XmlSchemaType;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@UtilityClass
public class ParticleVisitor {

    public static List<SchemaField> fieldPaths(XmlSchemaComplexType complexType, XmlSchema schema) {
        List<SchemaField> fields = new ArrayList<>();
        collectFieldPaths(complexType.getParticle(), schema, "", true, fields, new LinkedHashSet<>());
        return fields;
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
        if (particle instanceof XmlSchemaSequence sequence) {
            for (XmlSchemaSequenceMember item : sequence.getItems()) {
                collectSequenceMember(item, schema, prefix, parentRequired, fields, visiting);
            }
        }
        if (particle instanceof XmlSchemaChoice choice) {
            for (XmlSchemaChoiceMember item : choice.getItems()) {
                collectChoiceMember(item, schema, prefix, false, fields, visiting);
            }
        }
        if (particle instanceof XmlSchemaAll all) {
            for (XmlSchemaAllMember item : all.getItems()) {
                collectAllMember(item, schema, prefix, parentRequired, fields, visiting);
            }
        }
    }

    private void collectSequenceMember(
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

    private void collectChoiceMember(
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

    private void collectAllMember(
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

    private void collectElementField(
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
        XmlSchemaComplexType complexType = ComplexTypeResolver.resolve(resolvedElement, schema);

        if (complexType != null) {
            // JAXB Map<K,V> in XSD is sequence of repeating entry{key,value}.
            // Java beans expose a real Map (no getEntry()), so expand entry[] into SpEL would 502.
            if (!collection && isJaxbMapComplexType(complexType, schema)) {
                fields.add(new SchemaField(path, "object", required, false));
                return;
            }

            String typeKey = complexType.getName() != null ? complexType.getName() : path;
            if (!visiting.add(typeKey)) {
                fields.add(new SchemaField(collection ? path + "[]" : path, "object", required, collection));
                return;
            }

            List<SchemaField> nestedFields = new ArrayList<>();
            String nestedPrefix = collection ? path + "[]." : path + ".";
            collectFieldPaths(complexType.getParticle(), schema, nestedPrefix, required, nestedFields, visiting);
            visiting.remove(typeKey);

            if (collection) {
                fields.add(new SchemaField(path + "[]", "object", required, true));
            }
            if (!nestedFields.isEmpty()) {
                fields.addAll(nestedFields);
            } else if (!collection) {
                fields.add(new SchemaField(path, "object", required, false));
            }
            return;
        }

        fields.add(new SchemaField(collection ? path + "[]" : path, xsdTypeName(resolvedElement), required, collection));
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

    private String xsdTypeName(XmlSchemaElement element) {
        QName typeName = element.getSchemaTypeName();
        if (typeName != null) {
            return typeName.getLocalPart();
        }
        XmlSchemaType schemaType = element.getSchemaType();
        if (schemaType != null && schemaType.getQName() != null) {
            return schemaType.getQName().getLocalPart();
        }
        if (schemaType != null && schemaType.getName() != null) {
            return schemaType.getName();
        }
        return "string";
    }
}
