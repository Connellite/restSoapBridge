package io.github.connellite.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BridgePropertiesTest {

    @Test
    void normalizePathPrefix_addsLeadingAndStripsTrailingSlashes() {
        assertEquals("/api", BridgeProperties.normalizePathPrefix("/api"));
        assertEquals("/api", BridgeProperties.normalizePathPrefix("api"));
        assertEquals("/api", BridgeProperties.normalizePathPrefix("/api/"));
        assertEquals("/soap/v1", BridgeProperties.normalizePathPrefix(" soap/v1// "));
    }

    @Test
    void normalizePathPrefix_returnsEmptyForRoot() {
        assertEquals("", BridgeProperties.normalizePathPrefix("/"));
        assertEquals("", BridgeProperties.normalizePathPrefix(""));
        assertEquals("", BridgeProperties.normalizePathPrefix(null));
    }
}
