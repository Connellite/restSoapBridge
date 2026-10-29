package io.github.connellite.config;

import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.MappingRegistry;
import io.github.connellite.model.SchemaField;
import io.github.connellite.model.SchemaType;
import io.github.connellite.model.SoapFaultModel;
import io.github.connellite.service.auto.AutoMappingGenerator;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static io.github.connellite.config.OpenApiExampleFactory.exampleMap;
import static io.github.connellite.config.OpenApiExampleFactory.extractBodyField;
import static io.github.connellite.config.OpenApiExampleFactory.schemaWithXsdExamples;
import static io.github.connellite.config.OpenApiExampleFactory.xsdExample;

@Component
public class BridgeOpenApiBuilder {

    private static final Pattern INVALID_COMPONENT_KEY_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Set<String> QUERY_METHODS = Set.of("GET", "DELETE");

    /** Media type of the error bodies (problem-spring-web). */
    static final String PROBLEM_JSON = "application/problem+json";

    private final MappingRegistry mappingRegistry;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final BridgeProperties bridgeProperties;
    private volatile OpenAPI cachedOpenApi;

    public BridgeOpenApiBuilder(MappingRegistry mappingRegistry, ObjectProvider<BuildProperties> buildProperties) {
        this(mappingRegistry, buildProperties, new BridgeProperties());
    }

    @Autowired
    public BridgeOpenApiBuilder(MappingRegistry mappingRegistry, ObjectProvider<BuildProperties> buildProperties,
                                BridgeProperties bridgeProperties) {
        this.mappingRegistry = mappingRegistry;
        this.buildProperties = buildProperties;
        this.bridgeProperties = bridgeProperties;
    }

    public OpenAPI build() {
        OpenAPI local = cachedOpenApi;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cachedOpenApi == null) {
                cachedOpenApi = buildOpenApi();
            }
            return cachedOpenApi;
        }
    }

    private OpenAPI buildOpenApi() {
        Paths paths = new Paths();
        Components components = new Components();
        //noinspection rawtypes
        Map<String, Schema> schemas = new LinkedHashMap<>();
        Set<String> tagNames = new LinkedHashSet<>();
        TypeNames typeNames = TypeNames.assign(mappingRegistry.getMappings());

        for (MappingDefinition mapping : mappingRegistry.getMappings()) {
            String service = mapping.getSoap().getService();
            tagNames.add(service);

            SchemaRenderer renderer = new SchemaRenderer(mapping.getSchemaTypes(),
                    typeName -> typeNames.name(mapping.getSchemaTypes(), typeName));
            String requestSchemaName = schemaName(mapping, "Request");
            String responseSchemaName = schemaName(mapping, "Response");
            Iterable<String> requestPaths = schemaPaths(mapping.getRequestSchemaPaths(), mapping.getRequest().keySet());
            Iterable<String> responsePaths = schemaPaths(mapping.getResponseSchemaPaths(), mapping.getResponse().values());
            ObjectSchema requestSchema = bodySchema(renderer, mapping.getRequestType(),
                    mapping.getRequestSchemaFields(), requestPaths);
            ObjectSchema responseSchema = bodySchema(renderer, mapping.getResponseType(),
                    mapping.getResponseSchemaFields(), responsePaths);
            schemas.put(requestSchemaName, requestSchema);
            schemas.put(responseSchemaName, responseSchema);
            Map<String, ObjectSchema> faultSchemas = faultSchemas(mapping, renderer);
            schemas.putAll(faultSchemas);
            renderer.components().forEach(schemas::putIfAbsent);

            Operation operation = new Operation()
                    //.operationId(buildOperationId(mapping))
                    .tags(List.of(service))
                    .summary(mapping.getSoap().getOperation())
                    .description(buildDescription(mapping))
                    .responses(buildResponses(responseSchemaName, responseSchema, mapping, faultSchemas));
            List<String> pathVariables = AutoMappingGenerator.pathVariables(mapping.getPath());
            pathVariables.forEach(variable -> operation.addParametersItem(new PathParameter()
                    .name(variable)
                    .required(true)
                    .schema(parameterSchema(requestSchema, variable, schemas))));
            if (QUERY_METHODS.contains(mapping.getMethod().toUpperCase(Locale.ROOT))) {
                queryParameters(requestSchema, "", pathVariables, schemas,
                        Collections.newSetFromMap(new IdentityHashMap<>()))
                        .forEach(operation::addParametersItem);
            } else {
                pathVariables.forEach(variable -> removeRequired(requestSchema, variable));
                operation.requestBody(buildRequestBody(mapping, requestSchemaName, requestSchema));
            }

            PathItem pathItem = paths.getOrDefault(mapping.getPath(), new PathItem());
            applyHttpMethod(pathItem, mapping.getMethod(), operation);
            paths.addPathItem(mapping.getPath(), pathItem);
        }

        components.setSchemas(schemas);

        List<Tag> tags = new ArrayList<>();
        tagNames.stream().sorted().forEach(name -> tags.add(new Tag().name(name).description("SOAP service " + name)));

        return new OpenAPI()
                .info(new Info()
                        .title("REST SOAP Bridge")
                        .version(apiVersion())
                        .description("REST facade over SOAP services. Mapping mode: auto. Endpoints: "
                                + mappingRegistry.getMappings().size()))
                .tags(tags)
                .paths(paths)
                .components(components);
    }

    /**
     * Project version from {@code META-INF/build-info.properties} (spring-boot-maven-plugin {@code build-info}),
     * falling back to the JAR manifest when the app runs without it, e.g. from an IDE.
     */
    private String apiVersion() {
        BuildProperties build = buildProperties.getIfAvailable();
        if (build != null && build.getVersion() != null) {
            return build.getVersion();
        }
        String implementationVersion = BridgeOpenApiBuilder.class.getPackage().getImplementationVersion();
        return implementationVersion != null ? implementationVersion : "unknown";
    }

    /**
     * A request or response body: rendered from its type tree (named types as {@code $ref}) when the mapping has
     * one, otherwise from the field paths; the example comes from the field paths either way.
     */
    private static ObjectSchema bodySchema(SchemaRenderer renderer, SchemaType type, List<SchemaField> fields,
                                           Iterable<String> fallbackPaths) {
        if (type == null) {
            return schemaWithXsdExamples(fields, fallbackPaths);
        }
        ObjectSchema schema = renderer.object(type);
        SchemaRenderer.document(schema, type.documentation());
        schema.setExample(fields != null && !fields.isEmpty() ? xsdExample(fields) : exampleMap(fallbackPaths));
        return schema;
    }

    /** A component, or the {@code allOf} wrapper of one, resolved; any other schema as it is. */
    @SuppressWarnings("rawtypes")
    private static Schema<?> resolve(Schema<?> schema, Map<String, Schema> components) {
        Schema<?> current = schema;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current.get$ref() != null) {
                current = components.get(current.get$ref().substring(SchemaRenderer.COMPONENTS.length()));
            } else if (current.getType() == null && current.getAllOf() != null && current.getAllOf().size() == 1) {
                current = (Schema<?>) current.getAllOf().get(0);
            } else {
                return current;
            }
        }
        return current;
    }

    private RequestBody buildRequestBody(MappingDefinition mapping, String schemaName, ObjectSchema schema) {
        Map<String, Object> example = schema.getExample() instanceof Map<?, ?> map
                ? castExample(map)
                : exampleMap(schemaPaths(mapping.getRequestSchemaPaths(), mapping.getRequest().keySet()));

        MediaType mediaType = new MediaType()
                .schema(new Schema<>().$ref("#/components/schemas/" + schemaName))
                .example(example);
        return new RequestBody()
                .required(!mapping.getRequest().isEmpty())
                .content(new Content().addMediaType("application/json", mediaType));
    }

    /**
     * {@code GET}/{@code DELETE} take their fields from the query string: scalars and arrays of scalars,
     * nested objects as dotted names ({@code customer.name}). Arrays of objects and maps have no query form.
     */
    @SuppressWarnings("rawtypes")
    private static List<Parameter> queryParameters(Schema<?> schema, String prefix, List<String> pathVariables,
                                                   Map<String, Schema> components, Set<Schema<?>> visiting) {
        List<Parameter> parameters = new ArrayList<>();
        if (schema.getProperties() == null) {
            return parameters;
        }
        List<String> required = schema.getRequired() != null ? schema.getRequired() : List.of();
        for (Map.Entry<String, Schema> property : schema.getProperties().entrySet()) {
            String name = prefix + property.getKey();
            Schema<?> value = resolve(property.getValue(), components);
            if (pathVariables.contains(name) || value == null) {
                continue;
            }
            if ("object".equals(value.getType()) && value.getProperties() != null) {
                // A type that contains itself has no finite query form: stop at its second occurrence.
                if (visiting.add(value)) {
                    parameters.addAll(queryParameters(value, name + ".", pathVariables, components, visiting));
                    visiting.remove(value);
                }
            } else if (isQueryValue(value, components)) {
                parameters.add(new QueryParameter()
                        .name(name)
                        .required(prefix.isEmpty() && required.contains(property.getKey()))
                        .schema(value));
            }
        }
        return parameters;
    }

    @SuppressWarnings("rawtypes")
    private static boolean isQueryValue(Schema<?> schema, Map<String, Schema> components) {
        if (schema instanceof ArraySchema array) {
            Schema<?> items = resolve(array.getItems(), components);
            return items != null && isQueryValue(items, components);
        }
        return schema.getType() != null && !"object".equals(schema.getType());
    }

    /** Schema of the (possibly dotted) request field a path variable fills; a string when it is unknown. */
    @SuppressWarnings("rawtypes")
    private static Schema<?> parameterSchema(Schema<?> schema, String dottedName, Map<String, Schema> components) {
        Schema<?> current = schema;
        for (String segment : dottedName.split("\\.")) {
            Map<String, Schema> properties = current.getProperties();
            current = properties != null ? resolve(properties.get(segment), components) : null;
            if (current == null) {
                return new StringSchema();
            }
        }
        return current;
    }

    /** A field filled from the path need not be in the body. */
    private static void removeRequired(Schema<?> schema, String dottedName) {
        if (dottedName.contains(".") || schema.getRequired() == null) {
            return;
        }
        List<String> required = new ArrayList<>(schema.getRequired());
        required.remove(dottedName);
        schema.setRequired(required.isEmpty() ? null : required);
    }

    /**
     * One problem schema per declared fault, as {@code GlobalExceptionHandler} writes it: {@code faultName} fixed
     * to the fault, {@code faultInfo} shaped like the fault detail element.
     */
    private static Map<String, ObjectSchema> faultSchemas(MappingDefinition mapping, SchemaRenderer renderer) {
        Map<String, ObjectSchema> schemas = new LinkedHashMap<>();
        for (SoapFaultModel fault : mapping.getFaults() != null ? mapping.getFaults() : List.<SoapFaultModel>of()) {
            Schema<?> faultInfo = fault.type() != null
                    ? renderer.type(fault.type())
                    : schemaWithXsdExamples(fault.schemaFields(), List.of());
            ObjectSchema problem = new ObjectSchema();
            problem.addProperty("title", new StringSchema());
            problem.addProperty("status", new IntegerSchema());
            problem.addProperty("detail", new StringSchema());
            problem.addProperty("faultName", new StringSchema()._enum(List.of(fault.name())));
            problem.addProperty("faultInfo", faultInfo);
            problem.addProperty("correlationId", new StringSchema());
            problem.setRequired(List.of("faultName"));
            problem.setDescription(fault.documentation() != null
                    ? fault.documentation()
                    : "SOAP fault " + fault.name() + " declared in the WSDL");
            Map<String, Object> example = new LinkedHashMap<>();
            example.put("title", "SOAP Fault");
            example.put("faultName", fault.name());
            example.put("faultInfo", xsdExample(fault.schemaFields()));
            problem.setExample(example);
            schemas.put(schemaName(mapping, "Fault_" + fault.name()), problem);
        }
        return schemas;
    }

    private int faultStatus(String faultName) {
        Map<String, Integer> configured = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        configured.putAll(bridgeProperties.getErrors().getFaultStatus());
        Integer status = configured.get(faultName);
        return status != null ? status : 502;
    }

    @SuppressWarnings("rawtypes")
    private ApiResponses buildResponses(String schemaName, ObjectSchema schema, MappingDefinition mapping,
                                        Map<String, ObjectSchema> faultSchemas) {
        ApiResponses responses = buildResponses(schemaName, schema);
        Map<Integer, List<String>> faultsByStatus = new TreeMap<>();
        for (String faultSchemaName : faultSchemas.keySet()) {
            String faultName = String.valueOf(faultSchemas.get(faultSchemaName).getProperties().get("faultName")
                    .getEnum().get(0));
            faultsByStatus.computeIfAbsent(faultStatus(faultName), status -> new ArrayList<>()).add(faultSchemaName);
        }
        faultsByStatus.forEach((status, names) -> {
            List<Schema> refs = names.stream()
                    .map(name -> (Schema) new Schema<>().$ref("#/components/schemas/" + name))
                    .toList();
            Schema<?> faultSchema = refs.size() == 1 ? refs.get(0) : new ComposedSchema().oneOf(refs);
            String faults = "Declared SOAP fault(s): " + String.join(", ", names.stream()
                    .map(name -> name.substring(name.lastIndexOf("Fault_") + "Fault_".length())).toList());
            ApiResponse response = responses.get(String.valueOf(status));
            if (response == null) {
                response = new ApiResponse().description(faults);
                responses.addApiResponse(String.valueOf(status), response);
            } else {
                response.setDescription(response.getDescription() + ". " + faults);
            }
            Content content = response.getContent() != null ? response.getContent() : new Content();
            content.addMediaType(PROBLEM_JSON, new MediaType().schema(faultSchema));
            response.setContent(content);
        });
        return responses;
    }

    private ApiResponses buildResponses(String schemaName, ObjectSchema schema) {
        Map<String, Object> example = schema.getExample() instanceof Map<?, ?> map
                ? castExample(map)
                : Map.of();

        MediaType mediaType = new MediaType()
                .schema(new Schema<>().$ref("#/components/schemas/" + schemaName))
                .example(example);
        return new ApiResponses()
                .addApiResponse("200", new ApiResponse()
                        .description("Successful SOAP response")
                        .content(new Content().addMediaType("application/json", mediaType)))
                .addApiResponse("401", new ApiResponse().description("SOAP access denied"))
                .addApiResponse("502", new ApiResponse().description("Bridge or SOAP error"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castExample(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private String buildDescription(MappingDefinition mapping) {
        StringBuilder description = new StringBuilder();
        if (mapping.getDocumentation() != null) {
            description.append(mapping.getDocumentation()).append("\n\n");
        }
        description.append("SOAP service: ")
                .append(mapping.getSoap().getService())
                .append(", WSDL: ")
                .append(mapping.getSoap().getWsdl());
        if (!mapping.getRequest().isEmpty()) {
            description.append("\n\nRequest JSON fields: ");
            mapping.getRequest().keySet().forEach(key -> description.append(extractBodyField(key)).append(", "));
            description.setLength(description.length() - 2);
        }
        if (!mapping.getResponse().isEmpty()) {
            description.append("\n\nResponse JSON fields: ");
            mapping.getResponse().values().forEach(value -> description.append(extractBodyField(value)).append(", "));
            description.setLength(description.length() - 2);
        }
        return description.toString();
    }

    private void applyHttpMethod(PathItem pathItem, String method, Operation operation) {
        String normalized = method.toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "GET" -> pathItem.setGet(operation);
            case "PUT" -> pathItem.setPut(operation);
            case "DELETE" -> pathItem.setDelete(operation);
            case "PATCH" -> pathItem.setPatch(operation);
            default -> pathItem.setPost(operation);
        }
    }

    private Iterable<String> schemaPaths(List<String> explicitPaths, Iterable<String> fallbackPaths) {
        return explicitPaths != null && !explicitPaths.isEmpty() ? explicitPaths : fallbackPaths;
    }

    /**
     * OpenAPI component keys must match {@code ^[a-zA-Z0-9.\-_]+$}, otherwise code generators reject the spec.
     */
    static String schemaName(MappingDefinition mapping, String suffix) {
        String raw = mapping.getSoap().getService() + "_" + mapping.getSoap().getOperation() + suffix;
        return INVALID_COMPONENT_KEY_CHARS.matcher(raw).replaceAll("_");
    }

    private String buildOperationId(MappingDefinition mapping) {
        String raw = mapping.getMethod().toLowerCase(Locale.ROOT) + mapping.getPath().replace("/", "_");
        if (raw.startsWith("_")) {
            raw = raw.substring(1);
        }
        return raw.replace('_', ' ');
    }
}
