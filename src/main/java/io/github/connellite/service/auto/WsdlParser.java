package io.github.connellite.service.auto;

import io.github.connellite.model.WsdlOperationModel;
import io.github.connellite.model.SchemaField;
import io.github.connellite.model.SoapHeaderModel;
import io.github.connellite.config.BridgeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;

import javax.wsdl.Binding;
import javax.wsdl.BindingOperation;
import javax.wsdl.Definition;
import javax.wsdl.Message;
import javax.wsdl.Operation;
import javax.wsdl.Part;
import javax.wsdl.PortType;
import javax.wsdl.WSDLException;
import javax.wsdl.extensions.soap.SOAPBody;
import javax.wsdl.extensions.soap.SOAPHeader;
import javax.wsdl.extensions.soap12.SOAP12Body;
import javax.wsdl.extensions.soap12.SOAP12Header;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLReader;
import javax.xml.namespace.QName;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
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
        return operationCache.computeIfAbsent(wsdlUrl, this::parseOperationsFromWsdl);
    }

    private List<WsdlOperationModel> parseOperationsFromWsdl(String wsdlUrl) {
        try {
            WSDLReader reader = WSDLFactory.newInstance().newWSDLReader();
            reader.setFeature("javax.wsdl.verbose", false);
            Definition definition = readDefinition(reader, wsdlUrl);

            Map<String, List<String>> elementFields = XsdFieldIndex.readElementFields(definition);
            Map<String, List<SchemaField>> elementSchemaFields = XsdFieldIndex.readElementSchemaFields(definition);
            Map<String, SchemaField> simpleElements = XsdFieldIndex.readSimpleElements(definition);

            List<WsdlOperationModel> result = new ArrayList<>();
            for (Object value : definition.getAllPortTypes().values()) {
                PortType portType = (PortType) value;
                for (Object operationValue : portType.getOperations()) {
                    Operation operation = (Operation) operationValue;
                    String operationName = operation.getName();
                    if (operationName == null || operationName.isBlank()) {
                        continue;
                    }

                    BindingOperation binding = bindingOperation(definition, portType, operation);
                    Message inputMessage = operation.getInput() != null ? operation.getInput().getMessage() : null;
                    Message outputMessage = operation.getOutput() != null ? operation.getOutput().getMessage() : null;
                    List<?> inputExtensions = binding != null && binding.getBindingInput() != null
                            ? binding.getBindingInput().getExtensibilityElements()
                            : List.of();
                    List<?> outputExtensions = binding != null && binding.getBindingOutput() != null
                            ? binding.getBindingOutput().getExtensibilityElements()
                            : List.of();
                    String inputElement = bodyElement(inputMessage, inputExtensions, operationName);
                    String outputElement = bodyElement(outputMessage, outputExtensions, operationName + "Response");

                    List<SoapHeaderModel> inputHeaders = new ArrayList<>();
                    for (HeaderPart header : headerParts(definition, inputMessage, inputExtensions)) {
                        List<SchemaField> fields = elementSchemaFields.containsKey(header.element())
                                ? elementSchemaFields.get(header.element())
                                : simpleElements.containsKey(header.element())
                                ? List.of(simpleElements.get(header.element()))
                                : List.of();
                        inputHeaders.add(new SoapHeaderModel(header.part(), header.element(), fields));
                    }

                    result.add(new WsdlOperationModel(
                            operationName,
                            inputElement,
                            elementFields.getOrDefault(inputElement, List.of()),
                            elementSchemaFields.getOrDefault(inputElement, List.of()),
                            outputElement,
                            elementFields.getOrDefault(outputElement, List.of()),
                            elementSchemaFields.getOrDefault(outputElement, List.of()),
                            inputHeaders
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

    private static String rootMessage(Throwable failure) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message != null && !message.isBlank() ? " " + message : "");
    }

    /**
     * The binding operation of a port type operation; with several bindings (SOAP 1.1 and 1.2) the first one,
     * they declare the same body and headers.
     */
    private static BindingOperation bindingOperation(Definition definition, PortType portType, Operation operation) {
        for (Object value : definition.getAllBindings().values()) {
            Binding binding = (Binding) value;
            if (binding.getPortType() == null || !portType.getQName().equals(binding.getPortType().getQName())) {
                continue;
            }
            String inputName = operation.getInput() != null ? operation.getInput().getName() : null;
            String outputName = operation.getOutput() != null ? operation.getOutput().getName() : null;
            BindingOperation bindingOperation = binding.getBindingOperation(operation.getName(), inputName, outputName);
            if (bindingOperation != null) {
                return bindingOperation;
            }
        }
        return null;
    }

    /**
     * Element of the message part that goes into the SOAP body: the one {@code soap:body parts} names, otherwise
     * the first part that is not a {@code soap:header}. Message parts are unordered in wsdl4j, so the first part
     * alone may well be a header.
     */
    private static String bodyElement(Message message, List<?> bindingExtensions, String fallback) {
        if (message == null) {
            return fallback;
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
                return part.getElementName().getLocalPart();
            }
        }
        return fallback;
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
                headers.add(new HeaderPart(partName, part.getElementName().getLocalPart()));
            }
        }
        return headers;
    }

    private static boolean sameMessage(QName headerMessage, Message message) {
        return headerMessage == null || headerMessage.equals(message.getQName());
    }

    private record HeaderPart(String part, String element) {
    }
}
