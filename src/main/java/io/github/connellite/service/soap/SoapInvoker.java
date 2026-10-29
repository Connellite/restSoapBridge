package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.correlation.CorrelationId;
import io.github.connellite.exception.BackendFailures;
import io.github.connellite.exception.RequestValidationException;
import io.github.connellite.exception.UpstreamException;
import io.github.connellite.mapper.xml.JsonToXml;
import io.github.connellite.mapper.xml.XmlToJson;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.SchemaProperty;
import io.github.connellite.model.SchemaTrees;
import io.github.connellite.model.SoapFaultModel;
import io.github.connellite.model.SoapHeaderModel;
import io.github.connellite.model.SoapOperationContract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.headers.Header;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.message.Message;
import org.apache.cxf.staxutils.StaxUtils;
import org.springframework.stereotype.Component;
#if SPRING_BOOT_3
import jakarta.xml.ws.handler.MessageContext;
#else
import javax.xml.ws.handler.MessageContext;
#endif
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Calls a SOAP operation with a JSON body: the body becomes the request element as its XSD type describes it,
 * the SOAP headers object the header elements, and the response element comes back as JSON the same way.
 * No classes are generated: the CXF dispatch sends and receives the body element as XML.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SoapInvoker {

    private final SoapClientPool clientPool;
    private final BridgeProperties bridgeProperties;

    /**
     * @param body           REST request fields of the operation, the SOAP headers object included
     * @param backendHeaders HTTP headers for the SOAP request, in addition to the correlation id
     * @return the response element as JSON; an empty object for a one-way operation or an empty response
     */
    public ObjectNode invoke(MappingDefinition.SoapTarget target, JsonNode body, Map<String, List<String>> backendHeaders) {
        SoapOperationContract contract = target.getContract();
        if (contract == null || contract.inputElement() == null) {
            throw UpstreamException.contractMismatch("SOAP operation '" + target.getOperation()
                    + "' has no request element the bridge can send (RPC style is not supported)", null);
        }
        String headerRoot = bridgeProperties.getSoapHeaders().getJsonField();
        List<String> errors = new ArrayList<>();
        Element payload = payload(contract, target.getOperation(), body, headerRoot, errors);
        List<Header> headers = headers(contract, target.getOperation(), body != null ? body.get(headerRoot) : null,
                errors);
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
        if (log.isDebugEnabled()) {
            log.debug("Invoking SOAP operation '{}' with {}", target.getOperation(), abbreviate(StaxUtils.toString(payload)));
        }

        SoapPort port = port(target);
        Map<String, Object> context = port.dispatch().getRequestContext();
        Map<String, Object> callContext = requestContext(backendHeaders, CorrelationId.current(), headers);
        callContext.put(MessageContext.WSDL_OPERATION, QName.valueOf(contract.operation()));
        context.putAll(callContext);
        try {
            if (contract.oneWay()) {
                port.dispatch().invokeOneWay(new DOMSource(payload));
                return JsonNodeFactory.instance.objectNode();
            }
            Source response = port.dispatch().invoke(new DOMSource(payload));
            return response(port, contract, response);
        } catch (UpstreamException exception) {
            throw exception;
        } catch (Exception exception) {
            throw BackendFailures.classify(exception, bridgeProperties.getErrors().getFaultStatus(),
                    detail -> declaredFault(port, contract, detail));
        } finally {
            callContext.keySet().forEach(context::remove);
        }
    }

    private SoapPort port(MappingDefinition.SoapTarget target) {
        try {
            return clientPool.getPort(target.getWsdl(), target.getEndpoint(), target.getEndpointAddress());
        } catch (RuntimeException exception) {
            throw BackendFailures.clientCreation(target.getWsdl(), exception);
        }
    }

    /** The request element from the body; the SOAP headers object is not part of it. */
    private static Element payload(SoapOperationContract contract, String operation, JsonNode body, String headerRoot,
                                   List<String> errors) {
        JsonToXml writer = new JsonToXml(contract.types());
        Set<String> ignored = contract.headers().isEmpty() ? Set.of() : Set.of(headerRoot);
        String owner = "SOAP operation '" + operation + "'";
        Element payload = contract.inputSimple()
                ? writer.simple(QName.valueOf(contract.inputElement()), contract.inputType(), body, ignored, owner)
                : writer.object(QName.valueOf(contract.inputElement()), contract.inputType(), body, "$", ignored, owner);
        errors.addAll(writer.errors());
        return payload;
    }

    /** One header element per part sent in the SOAP headers object; an unknown part is a client error. */
    private static List<Header> headers(SoapOperationContract contract, String operation, JsonNode values,
                                        List<String> errors) {
        List<Header> headers = new ArrayList<>();
        if (values == null || values.isNull()) {
            return headers;
        }
        if (!values.isObject()) {
            errors.add("$.soapHeaders: object expected");
            return headers;
        }
        List<String> unknown = new ArrayList<>();
        for (Iterator<String> names = values.fieldNames(); names.hasNext(); ) {
            String part = names.next();
            if (!values.get(part).isNull() && contract.headers().stream().noneMatch(header -> header.part().equals(part))) {
                unknown.add(part);
            }
        }
        if (!unknown.isEmpty()) {
            errors.add("Unknown SOAP header(s) " + unknown + " for SOAP operation '" + operation + "'; expected "
                    + contract.headers().stream().map(SoapHeaderModel::part).toList());
        }
        for (SoapHeaderModel header : contract.headers()) {
            JsonNode value = values.get(header.part());
            if (value == null || value.isNull() || header.elementName() == null || header.property() == null) {
                continue;
            }
            QName name = QName.valueOf(header.elementName());
            JsonToXml writer = new JsonToXml(contract.types());
            Element element = writer.value(name, header.property(), value, "$.soapHeaders." + header.part());
            errors.addAll(writer.errors());
            // No data binding: SoapOutInterceptor copies DOM header elements as is.
            headers.add(new Header(name, element));
        }
        return headers;
    }

    private ObjectNode response(SoapPort port, SoapOperationContract contract, Source response) throws Exception {
        if (response == null) {
            return JsonNodeFactory.instance.objectNode();
        }
        Element element = StaxUtils.read(response).getDocumentElement();
        if (element == null) {
            return JsonNodeFactory.instance.objectNode();
        }
        validate(port, element, "SOAP response");
        XmlToJson reader = new XmlToJson(contract.types());
        if (contract.outputSimple()) {
            SchemaProperty value = contract.outputType().properties().get(0);
            ObjectNode json = JsonNodeFactory.instance.objectNode();
            json.set(SchemaTrees.restName(value.name()), reader.value(element, value));
            return json;
        }
        return reader.object(element, contract.outputType());
    }

    /** The declared fault a detail entry stands for, matched by element name. */
    private BackendFailures.DeclaredFault declaredFault(SoapPort port, SoapOperationContract contract, Element detail) {
        QName name = new QName(detail.getNamespaceURI() != null ? detail.getNamespaceURI() : "", localName(detail));
        for (SoapFaultModel fault : contract.faults()) {
            if (fault.element() != null && QName.valueOf(fault.element()).equals(name)) {
                validate(port, detail, "SOAP fault detail");
                JsonNode faultInfo = fault.type() != null
                        ? new XmlToJson(contract.types()).object(detail, fault.type())
                        : textOrNull(detail);
                return new BackendFailures.DeclaredFault(fault.name(), faultInfo);
            }
        }
        return null;
    }

    private static JsonNode textOrNull(Element element) {
        String text = DOMUtils.getContent(element);
        return text == null || text.isBlank() ? null : TextNode.valueOf(text.trim());
    }

    private static String localName(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getTagName();
    }

    /**
     * {@code bridge.validation.response}: the response element (or fault detail) against the schemas of the WSDL.
     * In {@code strict} mode a violation is a contract mismatch, in {@code log} mode a warning.
     */
    private void validate(SoapPort port, Element element, String what) {
        BridgeProperties.ResponseValidation mode = bridgeProperties.getValidation().getResponse();
        if (mode == null || mode == BridgeProperties.ResponseValidation.OFF) {
            return;
        }
        Schema schema = port.schema();
        if (schema == null) {
            return;
        }
        try {
            schema.newValidator().validate(new DOMSource(element));
        } catch (SAXException exception) {
            if (mode == BridgeProperties.ResponseValidation.STRICT) {
                throw UpstreamException.contractMismatch(what + " does not match the WSDL schema: "
                        + exception.getMessage(), exception);
            }
            log.warn("{} of {} does not match the WSDL schema: {}", what,
                    port.client().getEndpoint().getEndpointInfo().getName().getLocalPart(), exception.getMessage());
        } catch (IOException exception) {
            log.warn("{} could not be validated: {}", what, exception.getMessage());
        }
    }

    Map<String, Object> requestContext(Map<String, List<String>> backendHeaders, String correlationId,
                                       List<Header> soapHeaders) {
        Map<String, Object> context = new HashMap<>();
        BridgeProperties.Correlation correlation = bridgeProperties.getCorrelation();

        Map<String, List<String>> httpHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        httpHeaders.putAll(backendHeaders);
        if (correlationId != null && correlation.isForwardToBackend()) {
            httpHeaders.put(correlation.getHeaderName(), List.of(correlationId));
        }
        if (!httpHeaders.isEmpty()) {
            context.put(Message.PROTOCOL_HEADERS, httpHeaders);
        }

        List<Header> headers = new ArrayList<>(soapHeaders);
        if (correlationId != null && correlation.getSoapHeader().isEnabled()) {
            headers.add(correlationSoapHeader(correlation.getSoapHeader(), correlationId));
        }
        if (!headers.isEmpty()) {
            context.put(Header.HEADER_LIST, headers);
        }
        return context;
    }

    private static Header correlationSoapHeader(BridgeProperties.SoapHeader soapHeader, String correlationId) {
        QName name = new QName(soapHeader.getNamespace(), soapHeader.getLocalName());
        Element element = DOMUtils.createDocument().createElementNS(name.getNamespaceURI(), name.getLocalPart());
        element.setTextContent(correlationId);
        return new Header(name, element);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
