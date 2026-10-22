package io.github.connellite.mapper.path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
#if SPRING_BOOT_3
import jakarta.activation.DataHandler;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementRef;
#else
import javax.activation.DataHandler;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementRef;
#endif
import org.junit.jupiter.api.Test;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoapJsonMapperTest {

    private final ObjectMapper mapper = SoapJsonMapper.instance();

    @Test
    void convertValue_bindsXmlNamesToJaxbFields() {
        JaxbBean bean = mapper.convertValue(Map.of(
                "return", "ok",
                "class", "A",
                "item-id", 7,
                "lang", "ru",
                "tags", List.of("x", "y")
        ), JaxbBean.class);

        assertEquals("ok", bean._return);
        assertEquals("A", bean.clazz);
        assertEquals(7, bean.itemId);
        assertEquals("ru", bean.language);
        assertEquals(List.of("x", "y"), bean.tags);
    }

    @Test
    void valueToTree_writesXmlNames() {
        JaxbBean bean = new JaxbBean();
        bean._return = "ok";
        bean.clazz = "A";
        bean.itemId = 7;

        JsonNode json = mapper.valueToTree(bean);

        assertEquals("ok", json.get("return").asText());
        assertEquals("A", json.get("class").asText());
        assertEquals(7, json.get("item-id").asInt());
        assertTrue(json.get("_return") == null && json.get("clazz") == null, json.toString());
    }

    @Test
    void xmlGregorianCalendar_usesXsdLexicalFormInBothDirections() throws Exception {
        XMLGregorianCalendar calendar = DatatypeFactory.newInstance().newXMLGregorianCalendar("2024-01-02T03:04:05+03:00");

        assertEquals("\"2024-01-02T03:04:05+03:00\"", mapper.writeValueAsString(calendar));
        assertEquals(calendar, mapper.convertValue("2024-01-02T03:04:05+03:00", XMLGregorianCalendar.class));
    }

    @Test
    void jaxbElement_isWrittenAsItsValue() {
        JaxbBean bean = new JaxbBean();
        bean.nickname = new JAXBElement<>(new QName("nickname"), String.class, "neo");

        JsonNode json = mapper.valueToTree(bean);

        assertEquals("neo", json.get("nickname").asText());
    }

    @Test
    void dataHandlerProperty_keepsBridgeFormatInsideJaxbBeans() {
        JaxbBean bean = new JaxbBean();
        bean.attachment = new DataHandler("hello", "text/plain");

        JsonNode json = mapper.valueToTree(bean);

        assertEquals("text/plain", json.get("attachment").get("contentType").asText());
        assertEquals("aGVsbG8=", json.get("attachment").get("content").asText());
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class JaxbBean {
        @XmlElement(name = "return")
        String _return;
        @XmlElement(name = "class")
        String clazz;
        @XmlElement(name = "item-id")
        int itemId;
        @XmlAttribute(name = "lang")
        String language;
        @XmlElement
        List<String> tags = new ArrayList<>();
        @XmlElementRef(name = "nickname", type = JAXBElement.class)
        JAXBElement<String> nickname;
        DataHandler attachment;
    }
}
