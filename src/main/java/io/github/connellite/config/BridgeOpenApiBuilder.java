package io.github.connellite.config;

import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.MappingRegistry;
import io.github.connellite.service.auto.AutoMappingGenerator;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static io.github.connellite.config.OpenApiExampleFactory.exampleMap;
import static io.github.connellite.config.OpenApiExampleFactory.extractBodyField;
import static io.github.connellite.config.OpenApiExampleFactory.schemaWithXsdExamples;

@Component
@RequiredArgsConstructor
public class BridgeOpenApiBuilder {

    private static final Pattern INVALID_COMPONENT_KEY_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Set<String> QUERY_METHODS = Set.of("GET", "DELETE");

    private final MappingRegistry mappingRegistry;
    private final ObjectProvider<BuildProperties> buildProperties;
    private volatile OpenAPI cachedOpenApi;

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

        for (MappingDefinition mapping : mappingRegistry.getMappings()) {
            String service = mapping.getSoap().getService();
            tagNames.add(service);

            String requestSchemaName = schemaName(mapping, "Request");
            String responseSchemaName = schemaName(mapping, "Response");
            Iterable<String> requestPaths = schemaPaths(mapping.getRequestSchemaPaths(), mapping.getRequest().keySet());
            Iterable<String> responsePaths = schemaPaths(mapping.getResponseSchemaPaths(), mapping.getResponse().values());
            ObjectSchema requestSchema = schemaWithXsdExamples(mapping.getRequestSchemaFields(), requestPaths);
            ObjectSchema responseSchema = schemaWithXsdExamples(mapping.getResponseSchemaFields(), responsePaths);
            schemas.put(requestSchemaName, requestSchema);
            schemas.put(responseSchemaName, responseSchema);

            Operation operation = new Operation()
                    //.operationId(buildOperationId(mapping))
                    .tags(List.of(service))
                    .summary(mapping.getSoap().getOperation())
                    .description(buildDescription(mapping))
                    .responses(buildResponses(responseSchemaName, responseSchema));
            List<String> pathVariables = AutoMappingGenerator.pathVariables(mapping.getPath());
            pathVariables.forEach(variable -> operation.addParametersItem(new PathParameter()
                    .name(variable)
                    .required(true)
                    .schema(parameterSchema(requestSchema, variable))));
            if (QUERY_METHODS.contains(mapping.getMethod().toUpperCase(Locale.ROOT))) {
                queryParameters(requestSchema, "", pathVariables).forEach(operation::addParametersItem);
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
    private static List<Parameter> queryParameters(Schema<?> schema, String prefix, List<String> pathVariables) {
        List<Parameter> parameters = new ArrayList<>();
        if (schema.getProperties() == null) {
            return parameters;
        }
        List<String> required = schema.getRequired() != null ? schema.getRequired() : List.of();
        for (Map.Entry<String, Schema> property : schema.getProperties().entrySet()) {
            String name = prefix + property.getKey();
            Schema<?> value = property.getValue();
            if (pathVariables.contains(name)) {
                continue;
            }
            if ("object".equals(value.getType()) && value.getProperties() != null) {
                parameters.addAll(queryParameters(value, name + ".", pathVariables));
            } else if (isQueryValue(value)) {
                parameters.add(new QueryParameter()
                        .name(name)
                        .required(prefix.isEmpty() && required.contains(property.getKey()))
                        .schema(value));
            }
        }
        return parameters;
    }

    private static boolean isQueryValue(Schema<?> schema) {
        if (schema instanceof ArraySchema array) {
            return array.getItems() != null && isQueryValue(array.getItems());
        }
        return schema.getType() != null && !"object".equals(schema.getType());
    }

    /** Schema of the (possibly dotted) request field a path variable fills; a string when it is unknown. */
    @SuppressWarnings("rawtypes")
    private static Schema<?> parameterSchema(Schema<?> schema, String dottedName) {
        Schema<?> current = schema;
        for (String segment : dottedName.split("\\.")) {
            Map<String, Schema> properties = current.getProperties();
            current = properties != null ? properties.get(segment) : null;
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
        StringBuilder description = new StringBuilder("SOAP service: ")
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
