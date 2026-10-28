package io.github.connellite.service.auto.xsd;

import io.github.connellite.model.SchemaField;
import lombok.experimental.UtilityClass;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaCollection;
import org.apache.ws.commons.schema.XmlSchemaComplexType;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.apache.ws.commons.schema.XmlSchemaType;
import org.w3c.dom.Element;

import javax.wsdl.Definition;
import javax.wsdl.Types;
import javax.wsdl.extensions.schema.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@UtilityClass
public class SchemaIndexer {

    public static Map<String, List<SchemaField>> readElementSchemaFields(Definition definition) {
        Map<String, List<SchemaField>> result = new LinkedHashMap<>();
        readSchemas(definition).forEach(schema -> indexSchema(schema, result));
        return result;
    }

    /**
     * Global elements of a simple type (e.g. a {@code <token>} SOAP header) as a single field with an empty path.
     */
    public static Map<String, SchemaField> readSimpleElements(Definition definition) {
        Map<String, SchemaField> result = new LinkedHashMap<>();
        for (XmlSchema schema : readSchemas(definition)) {
            for (XmlSchemaElement element : schema.getElements().values()) {
                if (element.getName() == null || ComplexTypeResolver.resolve(element, schema) != null) {
                    continue;
                }
                SimpleTypeResolver.SimpleTypeInfo type =
                        SimpleTypeResolver.resolve(element.getSchemaTypeName(), element.getSchemaType(), schema);
                result.putIfAbsent(element.getName(), new SchemaField(type.list() ? "[]" : "", type.xsdType(),
                        false, type.list(), type.facets()));
            }
        }
        return result;
    }

    /** Every inline schema is read before indexing: a type may extend or use one declared in a later schema. */
    private static List<XmlSchema> readSchemas(Definition definition) {
        Types types = definition.getTypes();
        if (types == null) {
            return List.of();
        }
        XmlSchemaCollection collection = new XmlSchemaCollection();
        List<XmlSchema> schemas = new ArrayList<>();
        for (Object extension : types.getExtensibilityElements()) {
            if (extension instanceof Schema wsdlSchema) {
                Element schemaElement = wsdlSchema.getElement();
                schemas.add(collection.read(schemaElement, wsdlSchema.getDocumentBaseURI()));
            }
        }
        return schemas;
    }

    private static void indexSchema(XmlSchema schema, Map<String, List<SchemaField>> result) {
        for (XmlSchemaType type : schema.getSchemaTypes().values()) {
            if (type instanceof XmlSchemaComplexType complexType && complexType.getName() != null) {
                List<SchemaField> fields = ParticleVisitor.fieldPaths(complexType, schema);
                if (!fields.isEmpty()) {
                    result.put(complexType.getName(), fields);
                }
            }
        }

        for (XmlSchemaElement element : schema.getElements().values()) {
            if (element.getName() == null) {
                continue;
            }
            XmlSchemaComplexType complexType = ComplexTypeResolver.resolve(element, schema);
            if (complexType == null) {
                continue;
            }
            List<SchemaField> fields = ParticleVisitor.fieldPaths(complexType, schema);
            if (!fields.isEmpty()) {
                result.putIfAbsent(element.getName(), fields);
            }
        }
    }
}
