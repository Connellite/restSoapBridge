package io.github.connellite.config;

import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.SchemaProperty;
import io.github.connellite.model.SchemaTrees;
import io.github.connellite.model.SchemaType;
import io.github.connellite.model.SoapFaultModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Component names of the named XSD types of all mappings. A type whose local name is unique keeps it; where several
 * types share one, the first by namespace keeps the bare name and the others get a name derived from their namespace
 * in front ({@code billing_Item}), or the service name when the namespace is the same (one type in two WSDLs that
 * differ). A number is appended when that is still not enough. Types equal in every WSDL that uses them are one
 * component. Names are computed up front and do not depend on the order in which types are reached, and never clash
 * with the body and fault schemas of the operations.
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

        return buildComponentNames(used, taken);
    }

    /**
     * Sorted by local name, then namespace and service, so that the names and the residual-collision suffixes do not
     * depend on the order of the mappings.
     * <p>
     * Adapted from Membrane API Gateway (Apache License 2.0):
     * <a href="https://github.com/membrane/api-gateway/blob/2a3f9f13809774041cba4e34c3d1efd06b977c20/core/src/main/java/com/predic8/membrane/core/interceptor/wsdl2openapi/XsdDomUtil.java#L256-L270">XsdDomUtil.buildComponentNames</a>.
     */
    private static TypeNames buildComponentNames(List<Used> used, Set<String> taken) {
        Map<String, List<Used>> byLocalName = new TreeMap<>();
        for (Used type : used) {
            byLocalName.computeIfAbsent(SchemaRenderer.localName(type.qname()), ignored -> new ArrayList<>()).add(type);
        }
        TypeNames typeNames = new TypeNames();
        byLocalName.forEach((localName, types) -> {
            types.sort(Comparator.comparing((Used type) -> namespace(type.qname())).thenComparing(Used::service));
            List<Used> named = new ArrayList<>();
            String firstNamespace = namespace(types.get(0).qname());
            for (Used type : types) {
                Used same = named.stream().filter(known -> sameType(known, type)).findFirst().orElse(null);
                String name;
                if (same != null) {
                    name = typeNames.name(same.registry(), same.qname());
                } else {
                    String namespace = namespace(type.qname());
                    String candidate = named.isEmpty()
                            ? localName
                            : (namespace.equals(firstNamespace) ? type.service() : SchemaTrees.nsPrefix(namespace)) + "_" + localName;
                    name = uniqueName(SchemaRenderer.componentKey(candidate), taken);
                    named.add(type);
                }
                typeNames.names.computeIfAbsent(type.registry(), ignored -> new LinkedHashMap<>())
                        .put(type.qname(), name);
            }
        });
        return typeNames;
    }

    /**
     * {@code candidate}, suffixed until it is not among {@code taken}; adds the result to {@code taken}.
     * <p>
     * Adapted from Membrane API Gateway (Apache License 2.0):
     * <a href="https://github.com/membrane/api-gateway/blob/2a3f9f13809774041cba4e34c3d1efd06b977c20/core/src/main/java/com/predic8/membrane/core/interceptor/wsdl2openapi/XsdDomUtil.java#L290-L296">XsdDomUtil.uniqueName</a>.
     */
    private static String uniqueName(String candidate, Set<String> taken) {
        String name = candidate;
        for (int index = 2; !taken.add(name); index++) {
            name = candidate + "_" + index;
        }
        return name;
    }

    private static String namespace(String qname) {
        return qname.startsWith("{") ? qname.substring(1, qname.indexOf('}')) : "";
    }

    /** One type in two registries: equal, and so are the named types it refers to. */
    private static boolean sameType(Used first, Used second) {
        return first.qname().equals(second.qname())
                && sameType(first.registry(), second.registry(), first.qname(), new HashSet<>());
    }

    private static boolean sameType(Map<String, SchemaType> first, Map<String, SchemaType> second, String qname,
                                    Set<String> compared) {
        if (first == second || !compared.add(qname)) {
            return true;
        }
        SchemaType type = first.get(qname);
        if (type == null || !type.equals(second.get(qname))) {
            return false;
        }
        return references(type).stream().allMatch(reference -> sameType(first, second, reference, compared));
    }

    private static Set<String> references(SchemaType type) {
        Set<String> references = new LinkedHashSet<>();
        for (SchemaProperty property : type.properties()) {
            if (property.typeName() != null) {
                references.add(property.typeName());
            } else if (property.inlineType() != null) {
                references.addAll(references(property.inlineType()));
            }
        }
        return references;
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
