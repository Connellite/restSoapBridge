package io.github.connellite.service.auto.xsd;

import io.github.connellite.model.SchemaField;
import lombok.experimental.UtilityClass;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaCollection;
import org.apache.ws.commons.schema.XmlSchemaComplexType;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.w3c.dom.Element;

import javax.wsdl.Definition;
import javax.wsdl.Import;
import javax.wsdl.Types;
import javax.wsdl.extensions.schema.Schema;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@UtilityClass
public class SchemaIndexer {

    /**
     * Fields of the global elements of complex type, by element QName: two elements of one local name in
     * different namespaces are different message parts.
     */
    public static Map<QName, List<SchemaField>> readElementSchemaFields(Definition definition) {
        Map<QName, List<SchemaField>> result = new LinkedHashMap<>();
        readSchemas(definition).forEach(schema -> indexSchema(schema, result));
        return result;
    }

    /**
     * Global elements of a simple type (e.g. a {@code <token>} SOAP header) as a single field with an empty path.
     */
    public static Map<QName, SchemaField> readSimpleElements(Definition definition) {
        Map<QName, SchemaField> result = new LinkedHashMap<>();
        for (XmlSchema schema : readSchemas(definition)) {
            for (XmlSchemaElement element : schema.getElements().values()) {
                if (element.getName() == null || ComplexTypeResolver.resolve(element, schema) != null) {
                    continue;
                }
                SimpleTypeResolver.SimpleTypeInfo type =
                        SimpleTypeResolver.resolve(element.getSchemaTypeName(), element.getSchemaType(), schema);
                result.putIfAbsent(element.getQName(), new SchemaField(type.list() ? "[]" : "", type.xsdType(),
                        false, type.list(), type.facets()));
            }
        }
        return result;
    }

    /**
     * Every schema of the WSDL: the inline ones of the definition and of its {@code wsdl:import}s (CXF puts the
     * types of an interface in another namespace into {@code ?wsdl=Interface.wsdl}), plus the documents they
     * {@code xs:import}/{@code xs:include}. All are read into one collection before indexing: a type may extend
     * or use one declared in a later schema.
     */
    private static List<XmlSchema> readSchemas(Definition definition) {
        XmlSchemaCollection collection = new XmlSchemaCollection();
        List<Schema> inlineSchemas = new ArrayList<>();
        collectInlineSchemas(definition, inlineSchemas, Collections.newSetFromMap(new IdentityHashMap<>()));
        for (int index = 0; index < inlineSchemas.size(); index++) {
            Schema wsdlSchema = inlineSchemas.get(index);
            Element schemaElement = wsdlSchema.getElement();
            collection.read(schemaElement, systemId(wsdlSchema.getDocumentBaseURI(), index));
        }
        List<XmlSchema> schemas = new ArrayList<>();
        for (XmlSchema schema : collection.getXmlSchemas()) {
            if (!XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(schema.getTargetNamespace())) {
                schemas.add(schema);
            }
        }
        return schemas;
    }

    private static void collectInlineSchemas(Definition definition, List<Schema> schemas, Set<Definition> visited) {
        if (definition == null || !visited.add(definition)) {
            return;
        }
        Types types = definition.getTypes();
        if (types != null) {
            for (Object extension : types.getExtensibilityElements()) {
                if (extension instanceof Schema wsdlSchema) {
                    schemas.add(wsdlSchema);
                }
            }
        }
        for (Object imports : definition.getImports().values()) {
            for (Object value : (List<?>) imports) {
                collectInlineSchemas(((Import) value).getDefinition(), schemas, visited);
            }
        }
    }

    /**
     * The collection keys schemas by target namespace and system id, and the inline schemas of one WSDL share
     * its URL; a fragment per schema keeps two of one namespace apart (as CXF does) and still resolves relative
     * {@code schemaLocation}s against the WSDL.
     */
    private static String systemId(String documentBaseUri, int index) {
        return (documentBaseUri != null ? documentBaseUri : "") + "#types" + (index + 1);
    }

    private static void indexSchema(XmlSchema schema, Map<QName, List<SchemaField>> result) {
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
                result.putIfAbsent(element.getQName(), fields);
            }
        }
    }
}
