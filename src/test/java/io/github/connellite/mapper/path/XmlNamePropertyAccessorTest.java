package io.github.connellite.mapper.path;

#if SPRING_BOOT_3
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
#else
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
#endif
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XmlNamePropertyAccessorTest {

    @Test
    void readsJavaKeywordXmlNameInsteadOfGetClass() {
        assertEquals("A", SoapPathResolver.read(bean(), "#root.class"));
    }

    @Test
    void readsNonIdentifierXmlNameViaIndexer() {
        assertEquals(7, SoapPathResolver.read(bean(), "#root['item-id']"));
    }

    @Test
    void readsInheritedJaxbFieldAndNestedPath() {
        Child child = new Child();
        child.parentOnly = "base";
        child.nested = bean();

        assertEquals("base", SoapPathResolver.read(child, "#root['parent-only']"));
        assertEquals("A", SoapPathResolver.read(child, "#root.nested.class"));
    }

    @Test
    void regularPropertiesStillUseGetters() {
        assertEquals("plain", SoapPathResolver.read(bean(), "#root.plain"));
    }

    private static Bean bean() {
        Bean bean = new Bean();
        bean.clazz = "A";
        bean.itemId = 7;
        bean.plain = "plain";
        return bean;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class Bean {
        @XmlElement(name = "class")
        protected String clazz;
        @XmlElement(name = "item-id")
        protected int itemId;
        protected String plain;

        public String getPlain() {
            return plain;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class Parent {
        @XmlElement(name = "parent-only")
        protected String parentOnly;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class Child extends Parent {
        protected Bean nested;

        public Bean getNested() {
            return nested;
        }
    }
}
