package io.github.connellite.service.soap;

import org.apache.cxf.endpoint.Client;
import org.apache.cxf.service.model.ServiceInfo;
import org.apache.cxf.ws.addressing.EndpointReferenceUtils;

#if SPRING_BOOT_3
import jakarta.xml.ws.Dispatch;
#else
import javax.xml.ws.Dispatch;
#endif
import javax.xml.transform.Source;
import javax.xml.validation.Schema;

/**
 * One WSDL port the bridge calls: a CXF {@link Dispatch} in {@code PAYLOAD} mode (the body element goes in and comes
 * out as XML, no generated classes) and the CXF client under it, which carries transport and security settings.
 * The request context is per thread, so one port serves concurrent calls.
 */
public class SoapPort {

    private final Dispatch<Source> dispatch;
    private final Client client;
    private volatile Schema schema;

    public SoapPort(Dispatch<Source> dispatch, Client client) {
        this.dispatch = dispatch;
        this.client = client;
    }

    public Dispatch<Source> dispatch() {
        return dispatch;
    }

    public Client client() {
        return client;
    }

    /** The schemas of the WSDL for validating responses, built on first use. */
    public Schema schema() {
        Schema local = schema;
        if (local == null) {
            synchronized (this) {
                if (schema == null) {
                    ServiceInfo service = client.getEndpoint().getService().getServiceInfos().get(0);
                    schema = EndpointReferenceUtils.getSchema(service, client.getBus());
                }
                local = schema;
            }
        }
        return local;
    }

    public void destroy() {
        client.destroy();
    }
}
