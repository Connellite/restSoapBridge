package io.github.connellite.correlation;

import io.github.connellite.config.BridgeProperties;
#if SPRING_BOOT_3
import jakarta.servlet.FilterChain;
#else
import javax.servlet.FilterChain;
#endif
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter(new BridgeProperties());

    @Test
    void reusesIncomingIdForMdcAndResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/Auth/login");
        request.addHeader("X-Correlation-Id", "req-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, recordingChain(seenInChain));

        assertEquals("req-42", seenInChain.get());
        assertEquals("req-42", response.getHeader("X-Correlation-Id"));
        assertNull(CorrelationId.current());
    }

    @Test
    void generatesIdWhenMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/Auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, recordingChain(seenInChain));

        assertTrue(seenInChain.get().matches("[0-9a-f-]{36}"), seenInChain.get());
        assertEquals(seenInChain.get(), response.getHeader("X-Correlation-Id"));
    }

    @Test
    void replacesUnsafeIncomingId() {
        String resolved = CorrelationIdFilter.resolve("abc\r\nInjected: header");

        assertNotEquals("abc\r\nInjected: header", resolved);
        assertTrue(resolved.matches("[0-9a-f-]{36}"), resolved);
    }

    private static FilterChain recordingChain(AtomicReference<String> seen) {
        return (request, response) -> seen.set(CorrelationId.current());
    }
}
