package io.github.connellite.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.MappingRegistry;
import io.github.connellite.service.BridgeService;
import io.swagger.v3.oas.annotations.Hidden;
#if SPRING_BOOT_3
import jakarta.servlet.http.HttpServletRequest;
#else
import javax.servlet.http.HttpServletRequest;
#endif
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UrlPathHelper;

@Hidden
@RestController
@RequiredArgsConstructor
public class BridgeController {

    private final MappingRegistry mappingRegistry;
    private final BridgeService bridgeService;

    public ResponseEntity<JsonNode> handle(HttpServletRequest request, @RequestBody(required = false) JsonNode body) throws Exception {
        if (mappingRegistry.getMappings().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No mappings loaded. SOAP service discovery may have failed."
            );
        }

        // Mapping paths are relative to the application, so strip the servlet context path (WAR deployments).
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        MappingDefinition mapping = mappingRegistry.find(request.getMethod(), path)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No mapping for " + request.getMethod() + " " + request.getRequestURI()
                ));

        JsonNode requestBody = body != null ? body : JsonNodeFactory.instance.objectNode();
        JsonNode responseBody = bridgeService.execute(mapping, requestBody);
        return ResponseEntity.ok(responseBody);
    }
}
