package io.github.connellite.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.connellite.correlation.CorrelationId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zalando.problem.jackson.ProblemModule;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().registerModule(new ProblemModule())))
            .build();

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationId.MDC_KEY);
    }

    @Test
    void bugInsideBridgeIs500WithoutInternalMessage() throws Exception {
        mvc.perform(get("/npe"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("Unexpected NullPointerException in the bridge; see server logs"))
                .andExpect(content().string(not(containsString("secret internal state"))));
    }

    @Test
    void backendUnavailableKeepsItsStatusAndMessage() throws Exception {
        mvc.perform(get("/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("SOAP backend unavailable"))
                .andExpect(jsonPath("$.detail").value("SOAP backend is not reachable: Connection refused"));
    }

    @Test
    void soapFaultCarriesFaultDetails() throws Exception {
        mvc.perform(get("/fault"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("SOAP Fault"))
                .andExpect(jsonPath("$.detail").value("Token expired"))
                .andExpect(jsonPath("$.faultName").value("AccessDeniedException"))
                .andExpect(jsonPath("$.faultCode").value("{urn:soap}Server"))
                .andExpect(jsonPath("$.faultSubcodes[0]").value("{urn:elar}Expired"))
                .andExpect(jsonPath("$.faultInfo.code").value(17));
    }

    @Test
    void validationErrorsStay400() throws Exception {
        mvc.perform(get("/invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("$.uuid: unknown"));
    }

    @Test
    void configuredFaultStatusIsUsed() throws Exception {
        mvc.perform(get("/configured-status"))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void nonStandardFaultStatusFallsBackToBadGateway() throws Exception {
        mvc.perform(get("/non-standard-status"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void everyProblemCarriesCorrelationId() throws Exception {
        MDC.put(CorrelationId.MDC_KEY, "req-7");

        mvc.perform(get("/npe")).andExpect(jsonPath("$.correlationId").value("req-7"));
        mvc.perform(get("/fault")).andExpect(jsonPath("$.correlationId").value("req-7"));
        mvc.perform(get("/invalid")).andExpect(jsonPath("$.correlationId").value("req-7"));
    }

    @RestController
    static class FailingController {

        @GetMapping("/npe")
        String npe() {
            throw new NullPointerException("secret internal state");
        }

        @GetMapping("/unavailable")
        String unavailable() {
            throw UpstreamException.unavailable("SOAP backend is not reachable: Connection refused", null);
        }

        @GetMapping("/fault")
        String fault() {
            throw new SoapFaultException("AccessDeniedException", "Token expired", 401, "{urn:soap}Server",
                    List.of("{urn:elar}Expired"), JsonNodeFactory.instance.objectNode().put("code", 17), null);
        }

        @GetMapping("/invalid")
        String invalid() {
            throw new RequestValidationException(List.of("$.uuid: unknown"));
        }

        @GetMapping("/configured-status")
        String configuredStatus() {
            throw new SoapFaultException("ConflictException", "already exists", 409, null, null);
        }

        @GetMapping("/non-standard-status")
        String nonStandardStatus() {
            throw new SoapFaultException("OddFault", "odd", 499, null, null);
        }
    }
}
