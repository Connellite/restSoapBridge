package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
#if SPRING_BOOT_3
import jakarta.jws.Oneway;
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebService;
#else
import javax.jws.Oneway;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
#endif
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An operation without output message is sent one way: the call returns an empty body and does not wait for one.
 */
class OneWayIntegrationTest {

    private static final BlockingQueue<String> RECEIVED = new ArrayBlockingQueue<>(10);

    @Test
    void oneWayOperationIsSentAndAnswersWithEmptyBody() throws Exception {
        try (SoapTestBackend backend = SoapTestBackend.start("Notifier", NotifierImpl.class, new NotifierImpl())) {
            assertTrue(backend.mapping("notify").getSoap().getContract().oneWay());

            ObjectNode response = backend.execute("notify", new ObjectMapper().readTree("{\"text\": \"hi\"}"));

            assertEquals(0, response.size(), response.toString());
            assertEquals("hi", RECEIVED.poll(5, TimeUnit.SECONDS));
        }
    }

    @WebService(name = "Notifier", serviceName = "NotifierService", targetNamespace = "http://oneway.test/")
    public static class NotifierImpl {

        @Oneway
        @WebMethod
        public void notify(@WebParam(name = "text") String text) {
            RECEIVED.add(text);
        }
    }
}
