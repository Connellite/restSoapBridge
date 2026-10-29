package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.config.BridgeOpenApiBuilder;
import io.github.connellite.exception.RequestValidationException;
import io.github.connellite.mapper.xml.JsonToXml;
import io.github.connellite.model.SoapOperationContract;
import io.github.connellite.model.MappingRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
#if SPRING_BOOT_3
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebService;
import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
#else
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
#endif
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.namespace.QName;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST contract of the bridge for SOAP service (mock SEI with the same parameter shapes):
 * flat JSON maps, name-based arguments, complete DTO responses and explicit 400 for wrong field names.
 */
class ContractIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FOLDER_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String ATTR = "22222222-2222-2222-2222-222222222222";
    private static final AtomicReference<CreateDocumentCall> LAST_CREATE = new AtomicReference<>();

    private static SoapTestBackend backend;

    @BeforeAll
    static void start() throws Exception {
        backend = SoapTestBackend.start("RemoteMethodCall", ServiceImpl.class, new ServiceImpl());
    }

    @AfterAll
    static void stop() {
        backend.close();
    }

    // 1. Map contract -------------------------------------------------------------------------------------------

    @Test
    void remoteCall_flatMap_roundTrip() throws Exception {
        ObjectNode body = MAPPER.createObjectNode().put("token", "t").put("methodName", "echo");
        body.set("params", MAPPER.createObjectNode().put("msg", "hello"));

        ObjectNode response = backend.execute("call", body);

        JsonNode result = response.path("return");
        assertEquals("OK", result.path("STATUS").asText());
        assertEquals("hello", result.path("msg").asText());
        assertEquals("echo", result.path("SOURCE").asText());
        assertTrue(result.path("entry").isMissingNode(), result.toString());
        assertTrue(response.path("_return").isMissingNode(), response.toString());
    }

    @Test
    void remoteCall_acceptsKeyValueArray() throws Exception {
        ArrayNode params = MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("key", "msg").put("value", "hello"));
        ObjectNode body = MAPPER.createObjectNode().put("token", "t").put("methodName", "echo");
        body.set("params", params);

        ObjectNode response = backend.execute("call", body);

        assertEquals("OK", response.path("return").path("STATUS").asText());
        assertEquals("hello", response.path("return").path("msg").asText());
    }

    // 2. Name-based arguments -----------------------------------------------------------------------------------

    @Test
    void createDocument_jsonKeyOrderDoesNotMatter() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("files", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("key", "/tmp/a.jpg").put("value", "a.jpg")));
        body.put("folderUuid", FOLDER_UUID);
        body.set("attributeValues", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("key", ATTR).put("value", "x")));
        body.put("token", "t");

        ObjectNode response = backend.execute("createDocument", body);

        assertFalse(response.path("return").asText().isBlank(), response.toString());
        CreateDocumentCall call = LAST_CREATE.get();
        assertEquals("t", call.token());
        assertEquals(UUID.fromString(FOLDER_UUID), call.folderUuid());
        assertEquals(Map.of(ATTR, "x"), call.attributeValues());
        assertEquals(Map.of("/tmp/a.jpg", "a.jpg"), call.files());
    }

    @Test
    void createDocument_attributeValuesAsPlainObject() throws Exception {
        ObjectNode body = MAPPER.createObjectNode().put("token", "t").put("folderUuid", FOLDER_UUID);
        body.set("attributeValues", MAPPER.createObjectNode().put(ATTR, "x"));

        backend.execute("createDocument", body);

        assertEquals(Map.of(ATTR, "x"), LAST_CREATE.get().attributeValues());
    }

    /**
     * The request element without a SOAP call: elements in XSD order whatever the JSON order, a JAXB map as
     * entry/key/value elements, an empty entry list as no element at all.
     */
    @Test
    void requestPayload_followsXsdOrder() throws Exception {
        SoapOperationContract contract = backend.mapping("createDocument").getSoap().getContract();
        JsonToXml writer = new JsonToXml(contract.types());

        Element payload = writer.object(QName.valueOf(contract.inputElement()), contract.inputType(), MAPPER.readTree("""
                {"files": [{"key": "p", "value": "n"}], "token": "t", "folderUuid": "%s", "attributeValues": []}
                """.formatted(FOLDER_UUID)), "$", Set.of(), "createDocument");

        assertEquals(List.of(), writer.errors());
        List<String> children = new ArrayList<>();
        for (Node node = payload.getFirstChild(); node != null; node = node.getNextSibling()) {
            children.add(node.getLocalName());
        }
        assertEquals(List.of("token", "folderUuid", "files"), children);
        assertEquals("p", payload.getElementsByTagNameNS("*", "key").item(0).getTextContent());
    }

    // 3. Complex DTO response -----------------------------------------------------------------------------------

    @Test
    void getArchiveFolder_doesNotReturnAllNullSkeleton() throws Exception {
        ObjectNode response = backend.execute("getArchiveFolder",
                MAPPER.createObjectNode().put("token", "t").put("uuid", FOLDER_UUID));

        JsonNode folder = response.path("return");
        assertEquals(FOLDER_UUID, folder.path("uuid").asText());
        assertEquals("Archive", folder.path("name").asText());
        assertEquals(0, folder.path("minLevel").asInt(-1));
        assertEquals(3, folder.path("maxLevel").asInt(-1));
        List<JsonNode> values = new ArrayList<>();
        folder.elements().forEachRemaining(values::add);
        assertFalse(values.stream().allMatch(JsonNode::isNull), folder.toString());
    }

    // 4. OpenAPI contract ---------------------------------------------------------------------------------------

    @Test
    void openApi_mapFields_areObjectsWithStringAdditionalProperties() {
        OpenAPI openApi = new BridgeOpenApiBuilder(new MappingRegistry(backend.mappings()),
                new StaticListableBeanFactory().getBeanProvider(BuildProperties.class)).build();
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();

        Schema<?> params = (Schema<?>) schemas.get("RemoteMethodCall_callRequest").getProperties().get("params");
        assertEquals("object", params.getType());
        assertEquals("string", ((Schema<?>) params.getAdditionalProperties()).getType());
        assertTrue(params.getDescription().contains("flat JSON object"), params.getDescription());

        Schema<?> result = (Schema<?>) schemas.get("RemoteMethodCall_callResponse").getProperties().get("return");
        assertEquals("object", result.getType());
        assertEquals("string", ((Schema<?>) result.getAdditionalProperties()).getType());
    }

    // 5. Wrong parameter name -----------------------------------------------------------------------------------

    @Test
    void getBreadCrumbs_rejectsUuidAliasWithClear400() {
        ObjectNode body = MAPPER.createObjectNode().put("token", "t").put("uuid", FOLDER_UUID);

        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> backend.execute("getBreadCrumbs", body));

        assertEquals(List.of(
                "$: property 'uuid' is not defined in the schema and the schema does not allow additional properties",
                "$: allowed fields are [token, folderUuid]"
        ), exception.getErrors());
    }

    @Test
    void getBreadCrumbs_withFolderUuid() throws Exception {
        ObjectNode response = backend.execute("getBreadCrumbs",
                MAPPER.createObjectNode().put("token", "t").put("folderUuid", FOLDER_UUID));

        assertEquals(MAPPER.readTree("[\"Root\", \"" + FOLDER_UUID + "\"]"), response.path("return"));
    }

    // --- mock SEI ----------------------------------------------------------------------------------------------

    record CreateDocumentCall(String token, UUID folderUuid, Map<String, String> attributeValues, Map<String, String> files) {
    }

    public static class MapEntryStringString {
        public String key;
        public String value;
    }

    public static class ArchiveFolder {
        public String uuid;
        public String name;
        public int minLevel;
        public int maxLevel;
    }

    public static class UuidAdapter extends XmlAdapter<String, UUID> {

        @Override
        public UUID unmarshal(String value) {
            return value == null ? null : UUID.fromString(value);
        }

        @Override
        public String marshal(UUID value) {
            return value == null ? null : value.toString();
        }
    }

    @WebService(name = "RemoteMethodCall", serviceName = "RemoteMethodCallService", targetNamespace = "http://ws.example.test")
    public static class ServiceImpl {

        @WebMethod
        public Map<String, String> call(@WebParam(name = "token") String token,
                                        @WebParam(name = "methodName") String methodName,
                                        @WebParam(name = "params") Map<String, String> params) {
            Map<String, String> result = new HashMap<>(params);
            result.put("STATUS", "OK");
            result.put("SOURCE", methodName);
            return result;
        }

        @WebMethod
        public String createDocument(@WebParam(name = "token") String token,
                                     @WebParam(name = "folderUuid") @XmlJavaTypeAdapter(UuidAdapter.class) UUID folderUuid,
                                     @WebParam(name = "attributeValues") List<MapEntryStringString> attributeValues,
                                     @WebParam(name = "files") List<MapEntryStringString> files) {
            if (token == null || folderUuid == null) {
                throw new IllegalArgumentException("nFolder == null");
            }
            LAST_CREATE.set(new CreateDocumentCall(token, folderUuid, toMap(attributeValues), toMap(files)));
            return UUID.randomUUID().toString();
        }

        @WebMethod
        public ArchiveFolder getArchiveFolder(@WebParam(name = "token") String token, @WebParam(name = "uuid") String uuid) {
            ArchiveFolder folder = new ArchiveFolder();
            folder.uuid = uuid;
            folder.name = "Archive";
            folder.minLevel = 0;
            folder.maxLevel = 3;
            return folder;
        }

        @WebMethod
        public List<String> getBreadCrumbs(@WebParam(name = "token") String token,
                                           @WebParam(name = "folderUuid") String folderUuid) {
            if (folderUuid == null) {
                throw new IllegalArgumentException("folderUuidString == null");
            }
            return List.of("Root", folderUuid);
        }

        private static Map<String, String> toMap(List<MapEntryStringString> entries) {
            Map<String, String> map = new HashMap<>();
            if (entries != null) {
                entries.forEach(entry -> map.put(entry.key, entry.value));
            }
            return map;
        }
    }
}
