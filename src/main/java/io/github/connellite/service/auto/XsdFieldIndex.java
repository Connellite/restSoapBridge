package io.github.connellite.service.auto;

import io.github.connellite.model.SchemaField;
import io.github.connellite.service.auto.xsd.SchemaIndexer;

import javax.wsdl.Definition;
import javax.xml.namespace.QName;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class XsdFieldIndex {
    private XsdFieldIndex() {
    }

    static Map<QName, List<String>> readElementFields(Definition definition) {
        Map<QName, List<String>> result = new LinkedHashMap<>();
        readElementSchemaFields(definition).forEach((element, fields) ->
                result.put(element, fields.stream().map(SchemaField::path).toList()));
        return result;
    }

    static Map<QName, List<SchemaField>> readElementSchemaFields(Definition definition) {
        return SchemaIndexer.readElementSchemaFields(definition);
    }

    static Map<QName, SchemaField> readSimpleElements(Definition definition) {
        return SchemaIndexer.readSimpleElements(definition);
    }
}
