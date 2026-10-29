package io.github.connellite.service.auto.xsd;

import io.github.connellite.model.SchemaField;
import io.github.connellite.model.SchemaTrees;
import io.github.connellite.model.SchemaType;
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
     * The global elements of a WSDL, by element QName (two elements of one local name in different namespaces are
     * different message parts), with the named types they use.
     *
     * @param elementTypes   type of each global element of complex type that has fields
     * @param elementFields  the same elements as field paths
     * @param simpleElements global elements of a simple type (e.g. a {@code <token>} SOAP header) as a single field
     *                       with an empty path
     * @param types          named complex types by QName, shared by all elements of the WSDL
     */
    public record SchemaIndex(
            Map<QName, SchemaType> elementTypes,
            Map<QName, List<SchemaField>> elementFields,
            Map<QName, SchemaField> simpleElements,
            Map<String, SchemaType> types
    ) {
    }

    public static SchemaIndex readIndex(Definition definition) {
        List<XmlSchema> schemas = readSchemas(definition);
        Map<String, SchemaType> types = new LinkedHashMap<>();
        ParticleVisitor visitor = new ParticleVisitor(types);
        Map<QName, SchemaType> elementTypes = new LinkedHashMap<>();
        Map<QName, List<SchemaField>> elementFields = new LinkedHashMap<>();
        for (XmlSchema schema : schemas) {
            indexSchema(schema, visitor, types, elementTypes, elementFields);
        }
        return new SchemaIndex(elementTypes, elementFields, readSimpleElements(schemas), types);
    }

    public static Map<QName, List<SchemaField>> readElementSchemaFields(Definition definition) {
        return readIndex(definition).elementFields();
    }

    public static Map<QName, SchemaField> readSimpleElements(Definition definition) {
        return readSimpleElements(readSchemas(definition));
    }

    private static Map<QName, SchemaField> readSimpleElements(List<XmlSchema> schemas) {
        Map<QName, SchemaField> result = new LinkedHashMap<>();
        for (XmlSchema schema : schemas) {
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

    private static void indexSchema(XmlSchema schema, ParticleVisitor visitor, Map<String, SchemaType> types,
                                    Map<QName, SchemaType> elementTypes,
                                    Map<QName, List<SchemaField>> elementFields) {
        for (XmlSchemaElement element : schema.getElements().values()) {
            if (element.getName() == null || elementTypes.containsKey(element.getQName())) {
                continue;
            }
            XmlSchemaComplexType complexType = ComplexTypeResolver.resolve(element, schema);
            if (complexType == null) {
                continue;
            }
            SchemaType type = visitor.type(complexType, schema);
            List<SchemaField> fields = SchemaTrees.flatten(type, types);
            if (!fields.isEmpty()) {
                elementTypes.put(element.getQName(), type);
                elementFields.put(element.getQName(), fields);
            }
        }
    }
}
