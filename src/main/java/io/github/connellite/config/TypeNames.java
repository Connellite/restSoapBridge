package io.github.connellite.config;

import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.SchemaProperty;
import io.github.connellite.model.SchemaType;
import io.github.connellite.model.SoapFaultModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Component names of the named XSD types of all mappings. A type is published under its XSD name; when two different
 * types share one (other namespaces, other WSDLs), each gets the service name in front, and a number after when that
 * is still not enough. Names never clash with the body and fault schemas of the operations.
 */
final class TypeNames {

    /** Names by type registry (one per WSDL, compared by identity) and type QName. */
    private final Map<Map<String, SchemaType>, Map<String, String>> names = new IdentityHashMap<>();

    private TypeNames() {
    }

    static TypeNames assign(List<MappingDefinition> mappings) {
        Set<String> taken = new HashSet<>();
        for (MappingDefinition mapping : mappings) {
            taken.add(BridgeOpenApiBuilder.schemaName(mapping, "Request"));
            taken.add(BridgeOpenApiBuilder.schemaName(mapping, "Response"));
            for (SoapFaultModel fault : faults(mapping)) {
                taken.add(BridgeOpenApiBuilder.schemaName(mapping, "Fault_" + fault.name()));
            }
        }

        // Every named type a body or fault refers to, in order of first use, with the service it was met in.
        Map<Map<String, SchemaType>, Map<String, String>> services = new IdentityHashMap<>();
        List<Used> used = new ArrayList<>();
        for (MappingDefinition mapping : mappings) {
            Map<String, SchemaType> registry = mapping.getSchemaTypes() != null ? mapping.getSchemaTypes() : Map.of();
            Map<String, String> serviceOf = services.computeIfAbsent(registry, ignored -> new LinkedHashMap<>());
            List<SchemaType> roots = new ArrayList<>();
            roots.add(mapping.getRequestType());
            roots.add(mapping.getResponseType());
            faults(mapping).forEach(fault -> roots.add(fault.type()));
            for (SchemaType root : roots) {
                if (root == null) {
                    continue;
                }
                if (root.name() != null && registry.containsKey(root.name())) {
                    use(registry, root.name(), mapping, serviceOf, used);
                }
                collect(root, registry, mapping, serviceOf, used);
            }
        }

        Map<String, List<Used>> byLocalName = new LinkedHashMap<>();
        for (Used type : used) {
            byLocalName.computeIfAbsent(SchemaRenderer.componentKey(SchemaRenderer.localName(type.qname())),
                    ignored -> new ArrayList<>()).add(type);
        }
        TypeNames typeNames = new TypeNames();
        byLocalName.forEach((localName, types) -> {
            for (Used type : types) {
                String base = types.size() == 1 && !taken.contains(localName)
                        ? localName
                        : SchemaRenderer.componentKey(type.service() + "_" + localName);
                String name = base;
                for (int index = 2; !taken.add(name); index++) {
                    name = base + "_" + index;
                }
                typeNames.names.computeIfAbsent(type.registry(), ignored -> new LinkedHashMap<>())
                        .put(type.qname(), name);
            }
        });
        return typeNames;
    }

    /** Component name of a type; one that was not collected (not reachable from a body) by its XSD name. */
    String name(Map<String, SchemaType> registry, String qname) {
        Map<String, String> byQName = names.get(registry);
        String name = byQName != null ? byQName.get(qname) : null;
        return name != null ? name : SchemaRenderer.componentKey(SchemaRenderer.localName(qname));
    }

    private static List<SoapFaultModel> faults(MappingDefinition mapping) {
        return mapping.getFaults() != null ? mapping.getFaults() : List.of();
    }

    private static void collect(SchemaType type, Map<String, SchemaType> registry, MappingDefinition mapping,
                                Map<String, String> serviceOf, List<Used> used) {
        for (SchemaProperty property : type.properties()) {
            if (property.typeName() != null && registry.containsKey(property.typeName())) {
                if (use(registry, property.typeName(), mapping, serviceOf, used)) {
                    collect(registry.get(property.typeName()), registry, mapping, serviceOf, used);
                }
            } else if (property.inlineType() != null) {
                collect(property.inlineType(), registry, mapping, serviceOf, used);
            }
        }
    }

    /** Records the first use of a type; {@code false} when it was already recorded. */
    private static boolean use(Map<String, SchemaType> registry, String qname, MappingDefinition mapping,
                               Map<String, String> serviceOf, List<Used> used) {
        if (serviceOf.containsKey(qname)) {
            return false;
        }
        serviceOf.put(qname, mapping.getSoap().getService());
        used.add(new Used(registry, qname, mapping.getSoap().getService()));
        return true;
    }

    private record Used(Map<String, SchemaType> registry, String qname, String service) {
    }
}
