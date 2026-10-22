package io.github.connellite.config;

import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.MappingRegistry;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;
import lombok.RequiredArgsConstructor;
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

    private final MappingRegistry mappingRegistry;
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
                    .requestBody(buildRequestBody(mapping, requestSchemaName, requestSchema))
                    .responses(buildResponses(responseSchemaName, responseSchema));

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
                        .version("1.0")
                        .description("REST facade over SOAP services. Mapping mode: auto. Endpoints: "
                                + mappingRegistry.getMappings().size()))
                .tags(tags)
                .paths(paths)
                .components(components);
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
