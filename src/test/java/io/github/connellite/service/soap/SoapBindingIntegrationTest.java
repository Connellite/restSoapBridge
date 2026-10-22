package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.config.OpenApiExampleFactory;
import io.github.connellite.config.WsdlUrlCollector;
import io.github.connellite.exception.RequestValidationException;
import io.github.connellite.mapper.path.SoapArgument;
import io.github.connellite.model.DiscoveredSoapService;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.service.auto.AutoMappingGenerator;
import io.github.connellite.service.auto.WsdlParser;
import io.github.connellite.service.auto.WsdlServiceDiscovery;
import io.github.connellite.service.impl.BridgeServiceImpl;
import io.github.connellite.service.validation.RequestSchemaValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
#if SPRING_BOOT_3
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;
#else
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebResult;
import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;
#endif
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End to end through WSDL parsing, mapping generation and invocation against a real CXF endpoint:
 * argument binding by name, JAXB maps and entry lists (top level and nested), BARE operations that CXF
 * cannot unwrap, and response mapping failures.
 */
class SoapBindingIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Server server;
    private static String wsdlUrl;
    private static List<MappingDefinition> mappings;

    @BeforeAll
    static void startBackend() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        JaxWsServerFactoryBean factory = new JaxWsServerFactoryBean();
        factory.setServiceClass(RemoteMethodCallImpl.class);
        factory.setServiceBean(new RemoteMethodCallImpl());
        factory.setAddress("http://localhost:" + port + "/services/RemoteMethodCall");
        server = factory.create();
        wsdlUrl = "http://localhost:" + port + "/services/RemoteMethodCall?wsdl";

        WsdlServiceDiscovery discovery = mock(WsdlServiceDiscovery.class);
        WsdlUrlCollector collector = mock(WsdlUrlCollector.class);
        when(collector.wsdlUrls()).thenReturn(List.of());
        when(discovery.discover(null, List.of())).thenReturn(List.of(new DiscoveredSoapService(
                "RemoteMethodCall", wsdlUrl, new WsdlParser().parseOperations(wsdlUrl))));
        mappings = new AutoMappingGenerator(properties(), discovery, collector).generate();
    }

    @AfterAll
    static void stopBackend() {
        if (server != null) {
            server.destroy();
        }
    }

    // --- arguments by name -------------------------------------------------------------------------------------

    @Test
    void bindsArgumentsByNameWhateverTheMappingOrder() throws Exception {
        MappingDefinition mapping = copyWithReversedRequestOrder(mapping("createDocument"));

        ObjectNode response = execute(properties(), mapping, """
                {"pages": 3, "name": "Report", "token": "t-1", "folderUuid": "f-42"}
                """);

        JsonNode document = response.get("return");
        assertEquals("doc-1", document.get("uuid").asText());
        assertEquals("f-42", document.get("folderUuid").asText());
        assertEquals("Report", document.get("name").asText());
        assertEquals("t-1", document.get("owner").asText());
        assertEquals(3, document.get("pages").asInt());
    }

    @Test
    void invokerBindsShuffledArgumentsByName() {
        Object result = invoker(properties()).invoke(wsdlUrl, "createDocument", List.of(
                new SoapArgument("token", "t-2"),
                new SoapArgument("pages", "7"),
                new SoapArgument("folderUuid", "f-7"),
                new SoapArgument("name", "Scan")
        ));

        JsonNode document = io.github.connellite.mapper.path.SoapJsonMapper.instance().valueToTree(result);
        assertEquals("f-7", document.get("folderUuid").asText());
        assertEquals("t-2", document.get("owner").asText());
        assertEquals(7, document.get("pages").asInt());
    }

    @Test
    void unknownArgumentIsRejectedWithExpectedNames() {
        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> invoker(properties()).invoke(wsdlUrl, "createDocument", List.of(
                        new SoapArgument("folderUuid", "f-1"),
                        new SoapArgument("folderId", "typo")
                )));

        assertEquals(List.of("Unknown parameter(s) [folderId] for SOAP operation 'createDocument'; "
                + "expected [folderUuid, token, name, pages]"), exception.getErrors());
    }

    // --- JAXB maps ---------------------------------------------------------------------------------------------

    @Test
    void callsOperationWithMapParameter() throws Exception {
        ObjectNode response = execute(properties(), mapping("call"), """
                {"method": "search", "params": {"query": "abc", "limit": 10}}
                """);

        assertEquals("search{limit=10, query=abc}", response.get("return").asText());
    }

    @Test
    void mapRoundTripKeepsKeysAndUsesXmlNames() throws Exception {
        ObjectNode response = execute(properties(), mapping("echo"), """
                {"params": {"a": "1", "b": "2"}}
                """);

        assertEquals(MAPPER.readTree("{\"return\": {\"a\": \"1\", \"b\": \"2\"}}"), response);
    }

    @Test
    void mapParameterAcceptsEntryArray() throws Exception {
        ObjectNode response = execute(properties(), mapping("call"), """
                {"method": "m", "params": [{"key": "x", "value": "1"}, {"key": "", "value": "dropped"}]}
                """);

        assertEquals("m{x=1}", response.get("return").asText());
    }

    @Test
    void mapParameterIsPublishedAsObjectOfStrings() {
        ObjectSchema schema = OpenApiExampleFactory.schemaWithXsdExamples(mapping("call").getRequestSchemaFields(), List.of());

        Schema<?> params = schema.getProperties().get("params");
        assertEquals("object", params.getType());
        assertEquals("string", ((Schema<?>) params.getAdditionalProperties()).getType());
        assertEquals(Map.of("key", "value"), ((Map<?, ?>) schema.getExample()).get("params"));
    }

    @Test
    void stringInsteadOfMapIsRejectedBySchemaValidation() {
        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> execute(properties(), mapping("call"), """
                        {"method": "search", "params": "query=abc"}
                        """));

        // anyOf: a map may be an object or an entry array, so both expectations are reported
        assertEquals(List.of("$.params: string found, array expected", "$.params: string found, object expected"),
                exception.getErrors());
    }

    @Test
    void unusableMapInputIsRejectedBeforeCxfWhenValidationIsOff() {
        BridgeProperties properties = properties();
        properties.getValidation().setEnabled(false);

        RequestValidationException asString = assertThrows(RequestValidationException.class,
                () -> execute(properties, mapping("call"), """
                        {"method": "search", "params": "query=abc"}
                        """));
        RequestValidationException withoutEntries = assertThrows(RequestValidationException.class,
                () -> execute(properties, mapping("call"), """
                        {"method": "search", "params": [{"name": "query"}]}
                        """));

        assertTrue(asString.getErrors().get(0).startsWith("$.params: string cannot be converted to "),
                asString.getErrors().toString());
        assertTrue(withoutEntries.getErrors().get(0).contains("no key/value entries"),
                withoutEntries.getErrors().toString());
    }

    // --- List<entry> and nested DTO ----------------------------------------------------------------------------

    @Test
    void entryListParameterAcceptsPlainObjectAndEntryArray() throws Exception {
        ObjectNode fromObject = execute(properties(), mapping("setAttributes"), """
                {"uuid": "doc-1", "attrs": {"b": "2", "a": 1}}
                """);
        ObjectNode fromArray = execute(properties(), mapping("setAttributes"), """
                {"uuid": "doc-1", "attrs": [{"key": "a", "value": "1"}, {"key": "b", "value": "2"}]}
                """);

        assertEquals("doc-1:a=1,b=2", fromObject.get("return").asText());
        assertEquals("doc-1:a=1,b=2", fromArray.get("return").asText());
    }

    @Test
    void nestedDtoKeepsScalarsMapsAndEntryLists() throws Exception {
        ObjectNode response = execute(properties(), mapping("findDocuments"), """
                {"query": {"ctrlFolderUuid": "f-1", "attributes": {"x": "1"}, "labels": {"en": "Root"}}}
                """);

        assertEquals("f-1|x=1|en=Root", response.get("return").asText());
    }

    // --- operations CXF cannot unwrap (BARE) -------------------------------------------------------------------

    @Test
    void bareOperationBuildsRequestBeanFromFields() throws Exception {
        ObjectNode response = execute(properties(), mapping("search"), """
                {"query": "abc", "limit": 5}
                """);

        assertEquals(5, response.get("total").asInt());
        assertEquals("abc", response.get("first").asText());
    }

    @Test
    void bareOperationResponseWrapperIsStrippedLikeInGeneratedPaths() throws Exception {
        MappingDefinition mapping = mapping("lookup");

        ObjectNode response = execute(properties(), mapping, """
                {"uuid": "doc-9"}
                """);

        assertEquals("doc-9", response.get("document").get("uuid").asText());
        assertEquals("Found", response.get("document").get("name").asText());
    }

    // --- response mapping failures -----------------------------------------------------------------------------

    @Test
    void responseWithDataButOnlyNullMappedFieldsIsAnError() {
        MappingDefinition mapping = copy(mapping("createDocument"));
        mapping.setResponse(Map.of("#root.folder.uuid", "$.folder.uuid"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> execute(properties(), mapping, """
                        {"folderUuid": "f-1", "token": "t", "name": "n", "pages": 1}
                        """));

        assertTrue(exception.getMessage().contains("does not match response mapping [#root.folder.uuid]"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("\"uuid\":\"doc-1\""), exception.getMessage());
    }

    @Test
    void propertyMissingOnNonNullObjectIsNotSilentlyNull() {
        MappingDefinition mapping = copy(mapping("createDocument"));
        mapping.setResponse(Map.of("#root.uuid", "$.uuid", "#root.noSuchField", "$.x"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> execute(properties(), mapping, """
                        {"folderUuid": "f-1", "token": "t", "name": "n", "pages": 1}
                        """));

        assertTrue(exception.getMessage().contains("noSuchField"), exception.getMessage());
    }

    @Test
    void nullNestedObjectStillGivesNullLeaf() throws Exception {
        MappingDefinition mapping = copy(mapping("createDocument"));
        mapping.setResponse(new LinkedHashMap<>(Map.of("#root.uuid", "$.uuid", "#root.folder.uuid", "$.folderUuid")));

        ObjectNode response = execute(properties(), mapping, """
                {"folderUuid": "f-1", "token": "t", "name": "n", "pages": 1}
                """);

        assertEquals("doc-1", response.get("uuid").asText());
        assertTrue(response.get("folderUuid").isNull());
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private static BridgeProperties properties() {
        BridgeProperties properties = new BridgeProperties();
        BridgeProperties.AutoMapping auto = new BridgeProperties.AutoMapping();
        auto.setPathPrefix("/api");
        auto.setHttpMethod("POST");
        properties.setAuto(auto);
        return properties;
    }

    private static MappingDefinition mapping(String operation) {
        return mappings.stream()
                .filter(candidate -> candidate.getSoap().getOperation().equals(operation))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No mapping for " + operation + " in "
                        + mappings.stream().map(m -> m.getSoap().getOperation()).toList()));
    }

    private static MappingDefinition copy(MappingDefinition source) {
        MappingDefinition copy = new MappingDefinition();
        copy.setPath(source.getPath() + "#copy" + System.nanoTime());
        copy.setMethod(source.getMethod());
        copy.setSoap(source.getSoap());
        copy.setRequest(source.getRequest());
        copy.setResponse(source.getResponse());
        copy.setRequestSchemaFields(source.getRequestSchemaFields());
        copy.setResponseSchemaFields(source.getResponseSchemaFields());
        return copy;
    }

    private static MappingDefinition copyWithReversedRequestOrder(MappingDefinition source) {
        MappingDefinition copy = copy(source);
        List<Map.Entry<String, String>> entries = new ArrayList<>(source.getRequest().entrySet());
        Collections.reverse(entries);
        Map<String, String> reversed = new LinkedHashMap<>();
        entries.forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        copy.setRequest(reversed);
        return copy;
    }

    private static ObjectNode execute(BridgeProperties properties, MappingDefinition mapping, String json) throws Exception {
        SoapInvoker invoker = invoker(properties);
        return new BridgeServiceImpl(invoker, new RequestSchemaValidator(properties))
                .execute(mapping, MAPPER.readTree(json));
    }

    private static SoapInvoker invoker(BridgeProperties properties) {
        SoapClientPool pool = new SoapClientPool(properties, new SoapClientFactory(properties),
                new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
        return new SoapInvoker(pool, properties);
    }

    // --- backend -----------------------------------------------------------------------------------------------

    public static class Folder {
        public String uuid;
    }

    public static class Document {
        public String uuid;
        public String folderUuid;
        public String name;
        public String owner;
        public int pages;
        public Folder folder;
    }

    public static class Attr {
        public String key;
        public String value;
    }

    public static class Query {
        public String ctrlFolderUuid;
        public List<Attr> attributes;
        public Map<String, String> labels;
    }

    public static class SearchRequest {
        public String query;
        public int limit;
    }

    public static class SearchResult {
        public int total;
        public String first;
    }

    public static class LookupRequest {
        public String uuid;
    }

    public static class LookupResponse {
        public Document document;
    }

    @WebService(name = "RemoteMethodCall", serviceName = "RemoteMethodCallService", targetNamespace = "http://ws.test/")
    public static class RemoteMethodCallImpl {

        @WebMethod
        public String call(@WebParam(name = "method") String method, @WebParam(name = "params") Map<String, String> params) {
            return method + new TreeMap<>(params);
        }

        @WebMethod
        public Map<String, String> echo(@WebParam(name = "params") Map<String, String> params) {
            return new HashMap<>(params);
        }

        @WebMethod
        public Document createDocument(@WebParam(name = "folderUuid") String folderUuid,
                                       @WebParam(name = "token") String token,
                                       @WebParam(name = "name") String name,
                                       @WebParam(name = "pages") int pages) {
            if (folderUuid == null || token == null) {
                throw new IllegalArgumentException("nFolder == null");
            }
            Document document = new Document();
            document.uuid = "doc-1";
            document.folderUuid = folderUuid;
            document.name = name;
            document.owner = token;
            document.pages = pages;
            return document;
        }

        @WebMethod
        public String setAttributes(@WebParam(name = "uuid") String uuid, @WebParam(name = "attrs") List<Attr> attrs) {
            return uuid + ":" + join(attrs);
        }

        @WebMethod
        public String findDocuments(@WebParam(name = "query") Query query) {
            return query.ctrlFolderUuid + "|" + join(query.attributes) + "|" + joinMap(query.labels);
        }

        @WebMethod
        @SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.BARE)
        @WebResult(name = "searchResponse")
        public SearchResult search(@WebParam(name = "searchRequest") SearchRequest request) {
            SearchResult result = new SearchResult();
            result.total = request.limit;
            result.first = request.query;
            return result;
        }

        @WebMethod
        @SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.BARE)
        @WebResult(name = "lookupResponse")
        public LookupResponse lookup(@WebParam(name = "lookupRequest") LookupRequest request) {
            LookupResponse response = new LookupResponse();
            response.document = new Document();
            response.document.uuid = request.uuid;
            response.document.name = "Found";
            return response;
        }

        private static String join(List<Attr> attrs) {
            return attrs == null ? "" : attrs.stream()
                    .map(attr -> attr.key + "=" + attr.value)
                    .sorted()
                    .collect(Collectors.joining(","));
        }

        private static String joinMap(Map<String, String> map) {
            return map == null ? "" : new TreeMap<>(map).entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(","));
        }
    }
}
