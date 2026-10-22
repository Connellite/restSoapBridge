package io.github.connellite.correlation;

import io.github.connellite.config.BridgeProperties;
#if SPRING_BOOT_3
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
#else
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
#endif
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Takes the correlation id from the request header (or generates one), exposes it via MDC for logging
 * and the SOAP call, and echoes it in the response header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class CorrelationIdFilter extends OncePerRequestFilter {

    // Restrictive on purpose: the value ends up in logs and backend headers.
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final BridgeProperties bridgeProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String headerName = bridgeProperties.getCorrelation().getHeaderName();
        String correlationId = resolve(request.getHeader(headerName));
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(headerName, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    static String resolve(String incoming) {
        if (incoming != null && VALID_ID.matcher(incoming.trim()).matches()) {
            return incoming.trim();
        }
        return UUID.randomUUID().toString();
    }
}
