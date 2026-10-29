package io.github.connellite.service.auto;

import io.github.connellite.model.SoapFaultModel;
import io.github.connellite.model.SoapOperationContract;
import io.github.connellite.model.WsdlEndpoint;
import io.github.connellite.model.WsdlOperationModel;
import io.github.connellite.model.SchemaField;
import io.github.connellite.model.SoapHeaderModel;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.model.SchemaProperty;
import io.github.connellite.model.SchemaType;
import io.github.connellite.service.auto.xsd.Documentation;
import io.github.connellite.service.auto.xsd.SchemaIndexer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;

import javax.wsdl.Binding;
import javax.wsdl.BindingOperation;
import javax.wsdl.Definition;
import javax.wsdl.Fault;
import javax.wsdl.Message;
import javax.wsdl.Operation;
import javax.wsdl.Part;
import javax.wsdl.Port;
import javax.wsdl.PortType;
import javax.wsdl.WSDLException;
import javax.wsdl.extensions.soap.SOAPAddress;
import javax.wsdl.extensions.soap.SOAPBinding;
import javax.wsdl.extensions.soap.SOAPBody;
import javax.wsdl.extensions.soap.SOAPHeader;
import javax.wsdl.extensions.soap12.SOAP12Address;
import javax.wsdl.extensions.soap12.SOAP12Binding;
import javax.wsdl.extensions.soap12.SOAP12Body;
import javax.wsdl.extensions.soap12.SOAP12Header;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLReader;
import javax.xml.namespace.QName;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
// WSDL downloads must see the credentials of WsdlAuthenticator.
@DependsOn("wsdlAuthenticator")
public class WsdlParser {

    private final ConcurrentMap<String, List<WsdlOperationModel>> operationCache = new ConcurrentHashMap<>();
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public WsdlParser() {
        this(new BridgeProperties.AutoMapping());
    }

    @Autowired
    public WsdlParser(BridgeProperties bridgeProperties) {
        this(bridgeProperties.getAuto() != null ? bridgeProperties.getAuto() : new BridgeProperties.AutoMapping());
    }

    private WsdlParser(BridgeProperties.AutoMapping auto) {
        this.connectTimeout = auto.getConnectTimeout();
        this.readTimeout = auto.getReceiveTimeout();
    }

    public List<WsdlOperationModel> parseOperations(String wsdlUrl) {
        return parseOperations(wsdlUrl, null);
    }

    /**
     * @param preferredPort name of the WSDL port to expose ({@code bridge.backend.ports}); {@code null}: the first
     *                      SOAP 1.1 port, or SOAP 1.2 port when there is none
     */
    public List<WsdlOperationModel> parseOperations(String wsdlUrl, String preferredPort) {
        String key = preferredPort == null ? wsdlUrl : wsdlUrl + "#port=" + preferredPort;
        return operationCache.computeIfAbsent(key, ignored -> parseOperationsFromWsdl(wsdlUrl, preferredPort));
    }

    private List<WsdlOperationModel> parseOperationsFromWsdl(String wsdlUrl, String preferredPort) {
        try {
            WSDLReader reader = WSDLFactory.newInstance().newWSDLReader();
            reader.setFeature("javax.wsdl.verbose", false);
            Definition definition = readDefinition(reader, wsdlUrl);

            SchemaIndexer.SchemaIndex index = SchemaIndexer.readIndex(definition);
            Map<QName, List<SchemaField>> elementSchemaFields = index.elementFields();
            Map<QName, SchemaField> simpleElements = index.simpleElements();

            SelectedPort selected = selectPort(definition, wsdlUrl, preferredPort);
            List<PortType> portTypes = selected != null
                    ? List.of(selected.binding().getPortType())
                    : definition.getAllPortTypes().values().stream().map(PortType.class::cast).toList();
            WsdlEndpoint endpoint = selected != null ? selected.endpoint() : null;

            List<WsdlOperationModel> result = new ArrayList<>();
            for (PortType portType : portTypes) {
                for (Object operationValue : portType.getOperations()) {
                    Operation operation = (Operation) operationValue;
                    String operationName = operation.getName();
                    if (operationName == null || operationName.isBlank()) {
                        continue;
                    }

                    BindingOperation binding = selected != null
                            ? bindingOperation(selected.binding(), operation)
                            : bindingOperation(definition, portType, operation);
                    Message inputMessage = operation.getInput() != null ? operation.getInput().getMessage() : null;
                    Message outputMessage = operation.getOutput() != null ? operation.getOutput().getMessage() : null;
                    List<?> inputExtensions = binding != null && binding.getBindingInput() != null
                            ? binding.getBindingInput().getExtensibilityElements()
                            : List.of();
                    List<?> outputExtensions = binding != null && binding.getBindingOutput() != null
                            ? binding.getBindingOutput().getExtensibilityElements()
                            : List.of();
                    QName inputElement = bodyElement(inputMessage, inputExtensions);
                    QName outputElement = bodyElement(outputMessage, outputExtensions);
                    List<SchemaField> inputFields = elementSchemaFields.getOrDefault(inputElement, List.of());
                    List<SchemaField> outputFields = elementSchemaFields.getOrDefault(outputElement, List.of());

                    List<SoapHeaderModel> inputHeaders = new ArrayList<>();
                    for (HeaderPart header : headerParts(definition, inputMessage, inputExtensions)) {
                        List<SchemaField> fields = elementSchemaFields.containsKey(header.element())
                                ? elementSchemaFields.get(header.element())
                                : simpleElements.containsKey(header.element())
                                ? List.of(simpleElements.get(header.element()))
                                : List.of();
                        inputHeaders.add(new SoapHeaderModel(header.part(), header.element().getLocalPart(), fields,
                                headerProperty(header, index), header.element().toString()));
                    }

                    List<SoapFaultModel> faults = faults(operation, index);
                    SoapOperationContract contract = new SoapOperationContract(
                            new QName(portType.getQName().getNamespaceURI(), operationName).toString(),
                            inputElement != null ? inputElement.toString() : null,
                            outputElement != null ? outputElement.toString() : null,
                            operation.getOutput() == null,
                            index.elementTypes().get(inputElement),
                            index.elementTypes().get(outputElement),
                            inputHeaders,
                            faults,
                            index.types());
                    result.add(new WsdlOperationModel(
                            operationName,
                            inputElement != null ? inputElement.getLocalPart() : operationName,
                            paths(inputFields),
                            inputFields,
                            outputElement != null ? outputElement.getLocalPart() : operationName + "Response",
                            paths(outputFields),
                            outputFields,
                            inputHeaders,
                            faults,
                            Documentation.of(operation.getDocumentationElement()),
                            endpoint,
                            index.elementTypes().get(inputElement),
                            index.elementTypes().get(outputElement),
                            index.types(),
                            contract
                    ));
                }
            }
            return List.copyOf(result);
        } catch (WSDLException | RuntimeException exception) {
            throw new IllegalStateException("Failed to load WSDL " + wsdlUrl + ": " + rootMessage(exception), exception);
        }
    }

    private Definition readDefinition(WSDLReader reader, String wsdlUrl) throws WSDLException {
        TimeoutWsdlLocator locator = new TimeoutWsdlLocator(wsdlUrl, connectTimeout, readTimeout);
        try {
            return reader.readWSDL(locator);
        } finally {
            locator.close();
        }
    }

    private static List<String> paths(List<SchemaField> fields) {
        return fields.stream().map(SchemaField::path).toList();
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message != null && !message.isBlank() ? " " + message : "");
    }

    /**
     * The port the SOAP client calls, so that the published operations are the ones it can invoke: the configured
     * one, otherwise the first SOAP 1.1 port (SOAP 1.2 when there is none) in a stable order. {@code null} for a
     * WSDL without SOAP ports (an abstract interface document): then every port type is listed.
     */
    static SelectedPort selectPort(Definition definition, String wsdlUrl, String preferredPort) {
        List<SelectedPort> candidates = new ArrayList<>();
        for (Object serviceValue : definition.getAllServices().values()) {
            javax.wsdl.Service service = (javax.wsdl.Service) serviceValue;
            for (Object portValue : service.getPorts().values()) {
                Port port = (Port) portValue;
                Boolean soap12 = soapVersion12(port);
                if (soap12 != null && port.getBinding() != null && port.getBinding().getPortType() != null) {
                    candidates.add(new SelectedPort(
                            new WsdlEndpoint(service.getQName().toString(), port.getName()), port.getBinding(), soap12));
                }
            }
        }
        if (candidates.isEmpty()) {
            if (preferredPort != null) {
                throw new IllegalStateException("bridge.backend.ports: WSDL has no SOAP port " + preferredPort);
            }
            return null;
        }
        candidates.sort(Comparator.comparing(SelectedPort::soap12)
                .thenComparing(candidate -> candidate.endpoint().service())
                .thenComparing(candidate -> candidate.endpoint().port()));

        SelectedPort selected = preferredPort == null
                ? candidates.get(0)
                : candidates.stream()
                .filter(candidate -> candidate.endpoint().port().equalsIgnoreCase(preferredPort))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("bridge.backend.ports: WSDL has no SOAP port "
                        + preferredPort + "; ports: " + candidates.stream().map(c -> c.endpoint().port()).toList()));
        Set<String> skipped = new LinkedHashSet<>();
        for (SelectedPort candidate : candidates) {
            if (!candidate.binding().getPortType().getQName().equals(selected.binding().getPortType().getQName())) {
                skipped.add(candidate.endpoint().port());
            }
        }
        if (!skipped.isEmpty()) {
            log.warn("WSDL {}: exposing port {}; ports {} implement other port types and are not exposed "
                    + "(choose one with bridge.backend.ports)", wsdlUrl, selected.endpoint().port(), skipped);
        }
        return selected;
    }

    /** {@code false} for SOAP 1.1, {@code true} for SOAP 1.2, {@code null} for a port not bound to SOAP. */
    private static Boolean soapVersion12(Port port) {
        boolean soap11Address = port.getExtensibilityElements().stream().anyMatch(SOAPAddress.class::isInstance);
        boolean soap12Address = port.getExtensibilityElements().stream().anyMatch(SOAP12Address.class::isInstance);
        List<?> bindingExtensions = port.getBinding() != null ? port.getBinding().getExtensibilityElements() : List.of();
        if (soap11Address && bindingExtensions.stream().anyMatch(SOAPBinding.class::isInstance)) {
            return false;
        }
        if (soap12Address && bindingExtensions.stream().anyMatch(SOAP12Binding.class::isInstance)) {
            return true;
        }
        return null;
    }

    record SelectedPort(WsdlEndpoint endpoint, Binding binding, boolean soap12) {
    }

    private static BindingOperation bindingOperation(Binding binding, Operation operation) {
        String inputName = operation.getInput() != null ? operation.getInput().getName() : null;
        String outputName = operation.getOutput() != null ? operation.getOutput().getName() : null;
        return binding.getBindingOperation(operation.getName(), inputName, outputName);
    }

    /**
     * Without a SOAP port, the binding operation of a port type operation; with several bindings (SOAP 1.1 and 1.2)
     * the first one, they declare the same body and headers.
     */
    private static BindingOperation bindingOperation(Definition definition, PortType portType, Operation operation) {
        for (Object value : definition.getAllBindings().values()) {
            Binding binding = (Binding) value;
            if (binding.getPortType() == null || !portType.getQName().equals(binding.getPortType().getQName())) {
                continue;
            }
            BindingOperation bindingOperation = bindingOperation(binding, operation);
            if (bindingOperation != null) {
                return bindingOperation;
            }
        }
        return null;
    }

    /**
     * Declared faults of an operation. The name is the one the bridge reports as {@code faultName}: the dynamic
     * client names the fault exception after the fault message (JAX-WS), as a Java class name.
     */
    /**
     * A header part as an optional property: the element's type, its simple type, or an untyped object for an
     * element without indexed fields (e.g. {@code xs:any} content).
     */
    private static SchemaProperty headerProperty(HeaderPart header, SchemaIndexer.SchemaIndex index) {
        SchemaType type = index.elementTypes().get(header.element());
        if (type != null) {
            return new SchemaProperty(header.part(), "object", null, type.name(), type.name() == null ? type : null,
                    false, false, false, null, null, type.documentation(), List.of());
        }
        SchemaField simple = index.simpleElements().get(header.element());
        if (simple != null) {
            return new SchemaProperty(header.part(), simple.xsdType(), simple.facets(), null, null, false,
                    simple.collection(), false, null, null, null, List.of());
        }
        return new SchemaProperty(header.part(), "object", null, null, null, false, false, false, null, null, null,
                List.of());
    }

    private static List<SoapFaultModel> faults(Operation operation, SchemaIndexer.SchemaIndex index) {
        Map<QName, List<SchemaField>> elementSchemaFields = index.elementFields();
        Map<QName, SchemaField> simpleElements = index.simpleElements();
        List<SoapFaultModel> faults = new ArrayList<>();
        for (Object value : operation.getFaults().values()) {
            Fault fault = (Fault) value;
            Message message = fault.getMessage();
            if (message == null || message.getQName() == null) {
                continue;
            }
            Part part = ((List<?>) message.getOrderedParts(null)).stream()
                    .map(Part.class::cast)
                    .filter(candidate -> candidate.getElementName() != null)
                    .findFirst()
                    .orElse(null);
            List<SchemaField> fields = part == null ? List.of()
                    : elementSchemaFields.containsKey(part.getElementName())
                    ? elementSchemaFields.get(part.getElementName())
                    : simpleElements.containsKey(part.getElementName())
                    ? List.of(simpleElements.get(part.getElementName()))
                    : List.of();
            faults.add(new SoapFaultModel(javaClassName(message.getQName().getLocalPart()), fields,
                    Documentation.of(fault.getDocumentationElement()),
                    part != null ? index.elementTypes().get(part.getElementName()) : null,
                    part != null ? part.getElementName().toString() : null));
        }
        faults.sort(Comparator.comparing(SoapFaultModel::name));
        return faults;
    }

    /** JAXB's XML name to class name: words split at non-identifier characters, each capitalized. */
    static String javaClassName(String xmlName) {
        StringBuilder name = new StringBuilder();
        for (String word : xmlName.split("[^\\p{L}\\p{Nd}]+")) {
            if (!word.isEmpty()) {
                name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return name.toString();
    }

    /**
     * Element of the message part that goes into the SOAP body: the one {@code soap:body parts} names, otherwise
     * the first part that is not a {@code soap:header}. Message parts are unordered in wsdl4j, so the first part
     * alone may well be a header. {@code null} without such a part (no message, RPC {@code type} parts).
     */
    private static QName bodyElement(Message message, List<?> bindingExtensions) {
        if (message == null) {
            return null;
        }
        List<?> bodyParts = null;
        Set<String> headerParts = new HashSet<>();
        for (Object extension : bindingExtensions) {
            if (extension instanceof SOAPBody body) {
                bodyParts = body.getParts();
            } else if (extension instanceof SOAP12Body body) {
                bodyParts = body.getParts();
            } else if (extension instanceof SOAPHeader header && sameMessage(header.getMessage(), message)) {
                headerParts.add(header.getPart());
            } else if (extension instanceof SOAP12Header header && sameMessage(header.getMessage(), message)) {
                headerParts.add(header.getPart());
            }
        }
        for (Object value : message.getOrderedParts(null)) {
            Part part = (Part) value;
            boolean body = bodyParts != null && !bodyParts.isEmpty()
                    ? bodyParts.contains(part.getName())
                    : !headerParts.contains(part.getName());
            if (body && part.getElementName() != null) {
                return part.getElementName();
            }
        }
        return null;
    }

    /**
     * {@code soap:header} parts of a binding message: explicit ones (a part of the operation message) and
     * implicit ones (a part of another message). Headers of {@code type} parts (RPC) are not supported.
     */
    private static List<HeaderPart> headerParts(Definition definition, Message operationMessage, List<?> bindingExtensions) {
        List<HeaderPart> headers = new ArrayList<>();
        for (Object extension : bindingExtensions) {
            QName messageName;
            String partName;
            if (extension instanceof SOAPHeader header) {
                messageName = header.getMessage();
                partName = header.getPart();
            } else if (extension instanceof SOAP12Header header) {
                messageName = header.getMessage();
                partName = header.getPart();
            } else {
                continue;
            }
            Message message = messageName == null ? operationMessage : definition.getMessage(messageName);
            Part part = message != null && partName != null ? message.getPart(partName) : null;
            if (part == null || part.getElementName() == null) {
                log.warn("Ignoring SOAP header {} of message {}: not an element part", partName, messageName);
                continue;
            }
            if (headers.stream().noneMatch(existing -> existing.part().equals(partName))) {
                headers.add(new HeaderPart(partName, part.getElementName()));
            }
        }
        return headers;
    }

    private static boolean sameMessage(QName headerMessage, Message message) {
        return headerMessage == null || headerMessage.equals(message.getQName());
    }

    private record HeaderPart(String part, QName element) {
    }
}
