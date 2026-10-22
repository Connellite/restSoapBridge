package io.github.connellite.correlation;

import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

/**
 * Correlation id of the REST request being handled on the current thread.
 */
@UtilityClass
public class CorrelationId {

    /** MDC key; use {@code %X{correlationId}} in log patterns. */
    public static final String MDC_KEY = "correlationId";

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
