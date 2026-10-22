package io.github.connellite.controller;

import io.github.connellite.config.BridgeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Registers {@link BridgeController#handle} under the configured {@code bridge.auto.path-prefix}.
 */
@Slf4j
@Component
public class BridgeRouteRegistrar implements SmartInitializingSingleton {

    private final RequestMappingHandlerMapping handlerMapping;
    private final BridgeController bridgeController;
    private final BridgeProperties bridgeProperties;

    // Explicit qualifier: actuator also exposes RequestMappingHandlerMapping subclasses.
    public BridgeRouteRegistrar(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            BridgeController bridgeController,
            BridgeProperties bridgeProperties
    ) {
        this.handlerMapping = handlerMapping;
        this.bridgeController = bridgeController;
        this.bridgeProperties = bridgeProperties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        String prefix = BridgeProperties.normalizePathPrefix(
                bridgeProperties.getAuto() != null ? bridgeProperties.getAuto().getPathPrefix() : null);
        if (prefix.isEmpty()) {
            throw new IllegalStateException(
                    "bridge.auto.path-prefix must not be the root path: it would shadow Swagger UI and actuator");
        }

        String pattern = prefix + "/**";
        RequestMappingInfo mappingInfo = RequestMappingInfo.paths(pattern)
                .options(handlerMapping.getBuilderConfiguration())
                .build();
        handlerMapping.registerMapping(mappingInfo, bridgeController, handleMethod());
        log.info("Bridge endpoints mapped to {}", pattern);
    }

    private static Method handleMethod() {
        return Arrays.stream(BridgeController.class.getMethods())
                .filter(method -> method.getName().equals("handle"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("BridgeController.handle not found"));
    }
}
