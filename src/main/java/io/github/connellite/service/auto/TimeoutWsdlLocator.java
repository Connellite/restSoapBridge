package io.github.connellite.service.auto;

import org.xml.sax.InputSource;

import javax.wsdl.xml.WSDLLocator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads a WSDL and its {@code wsdl:import}s with connect/read timeouts: wsdl4j's default
 * {@code URL.openStream()} has none, and an unreachable host would block application startup indefinitely.
 */
final class TimeoutWsdlLocator implements WSDLLocator {

    private final String baseUri;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final List<InputStream> openedStreams = new ArrayList<>();
    private String latestImportUri;

    TimeoutWsdlLocator(String baseUri, Duration connectTimeout, Duration readTimeout) {
        this.baseUri = baseUri;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public InputSource getBaseInputSource() {
        return open(baseUri);
    }

    @Override
    public InputSource getImportInputSource(String parentLocation, String importLocation) {
        String resolved = parentLocation != null ? URI.create(parentLocation).resolve(importLocation).toString() : importLocation;
        latestImportUri = resolved;
        return open(resolved);
    }

    @Override
    public String getBaseURI() {
        return baseUri;
    }

    @Override
    public String getLatestImportURI() {
        return latestImportUri;
    }

    @Override
    public void close() {
        for (InputStream stream : openedStreams) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // nothing to do: the document is already parsed
            }
        }
        openedStreams.clear();
    }

    private InputSource open(String location) {
        try {
            URLConnection connection = new URL(location).openConnection();
            connection.setConnectTimeout((int) connectTimeout.toMillis());
            connection.setReadTimeout((int) readTimeout.toMillis());
            InputStream stream = connection.getInputStream();
            openedStreams.add(stream);
            InputSource source = new InputSource(stream);
            source.setSystemId(location);
            return source;
        } catch (IOException exception) {
            // WSDLLocator cannot throw checked exceptions; WsdlParser unwraps it into its failure message
            throw new UncheckedIOException(exception);
        }
    }
}
