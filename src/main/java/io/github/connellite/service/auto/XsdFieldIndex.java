package io.github.connellite.service.auto;

import io.github.connellite.model.SchemaField;
import io.github.connellite.service.auto.xsd.SchemaIndexer;

import javax.wsdl.Definition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class XsdFieldIndex {
    private XsdFieldIndex() {
    }

    static Map<String, List<String>> readElementFields(Definition definition) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        readElementSchemaFields(definition).forEach((element, fields) ->
                result.put(element, fields.stream().map(SchemaField::path).toList()));
        return result;
    }

    static Map<String, List<SchemaField>> readElementSchemaFields(Definition definition) {
        return SchemaIndexer.readElementSchemaFields(definition);
    }

    static Map<String, SchemaField> readSimpleElements(Definition definition) {
        return SchemaIndexer.readSimpleElements(definition);
    }
}
