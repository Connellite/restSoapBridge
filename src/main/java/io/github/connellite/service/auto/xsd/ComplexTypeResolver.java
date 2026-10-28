package io.github.connellite.service.auto.xsd;

import lombok.experimental.UtilityClass;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaCollection;
import org.apache.ws.commons.schema.XmlSchemaComplexType;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.apache.ws.commons.schema.XmlSchemaType;

import javax.xml.namespace.QName;

@UtilityClass
public class ComplexTypeResolver {

    public static XmlSchemaComplexType resolve(XmlSchemaElement element, XmlSchema schema) {
        XmlSchemaElement resolvedElement = resolveElementReference(element);
        if (resolvedElement != element) {
            return resolve(resolvedElement, schema);
        }
        XmlSchemaType inlineType = element.getSchemaType();
        if (inlineType instanceof XmlSchemaComplexType complexType) {
            return complexType;
        }
        if (element.getSchemaTypeName() == null) {
            return null;
        }
        XmlSchemaType resolvedType = lookupType(element.getSchemaTypeName(), schema);
        return resolvedType instanceof XmlSchemaComplexType complexType ? complexType : null;
    }

    public static XmlSchemaElement resolveElementReference(XmlSchemaElement element) {
        if (!element.isRef()) {
            return element;
        }
        XmlSchemaElement target = element.getRef().getTarget();
        return target != null ? target : element;
    }

    /**
     * A WSDL usually carries one inline schema per namespace, importing the others without a
     * {@code schemaLocation}; such types are found through the collection, not the schema's own imports.
     */
    public static XmlSchemaType lookupType(QName typeName, XmlSchema schema) {
        if (typeName == null) {
            return null;
        }
        XmlSchemaType type = schema.getTypeByName(typeName);
        XmlSchemaCollection collection = schema.getParent();
        return type == null && collection != null ? collection.getTypeByQName(typeName) : type;
    }
}
