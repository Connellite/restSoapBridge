package io.github.connellite.service.auto;

import io.github.connellite.model.WsdlOperationModel;
import io.github.connellite.model.SchemaField;
import io.github.connellite.config.BridgeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;

import javax.wsdl.Definition;
import javax.wsdl.Input;
import javax.wsdl.Message;
import javax.wsdl.Operation;
import javax.wsdl.Output;
import javax.wsdl.Part;
import javax.wsdl.PortType;
import javax.wsdl.WSDLException;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLReader;
import javax.xml.namespace.QName;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
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

            Map<String, String> messageToElement = readMessageElements(definition);
            Map<String, List<String>> elementFields = XsdFieldIndex.readElementFields(definition);
            Map<String, List<SchemaField>> elementSchemaFields = XsdFieldIndex.readElementSchemaFields(definition);

            List<WsdlOperationModel> result = new ArrayList<>();
            for (Object value : definition.getAllPortTypes().values()) {
                PortType portType = (PortType) value;
                for (Object operationValue : portType.getOperations()) {
                    Operation operation = (Operation) operationValue;
                    String operationName = operation.getName();
                    if (operationName == null || operationName.isBlank()) {
                        continue;
                    }

                    String inputMessage = messageName(operation.getInput());
                    String outputMessage = messageName(operation.getOutput());
                    String inputElement = messageToElement.getOrDefault(inputMessage, operationName);
                    String outputElement = messageToElement.getOrDefault(outputMessage, operationName + "Response");

                    List<String> inputFields = elementFields.getOrDefault(inputElement, List.of());
                    List<String> outputFields = elementFields.getOrDefault(outputElement, List.of());
                    List<SchemaField> inputSchemaFields = elementSchemaFields.getOrDefault(inputElement, List.of());
                    List<SchemaField> outputSchemaFields = elementSchemaFields.getOrDefault(outputElement, List.of());
                    result.add(new WsdlOperationModel(
                            operationName,
                            inputElement,
                            inputFields,
                            inputSchemaFields,
                            outputElement,
                            outputFields,
                            outputSchemaFields
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

    private Map<String, String> readMessageElements(Definition definition) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Object value : definition.getMessages().values()) {
            Message message = (Message) value;
            if (message.getParts().isEmpty()) {
                continue;
            }
            Part part = (Part) message.getParts().values().iterator().next();
            QName elementName = part.getElementName();
            if (elementName != null) {
                result.put(message.getQName().getLocalPart(), elementName.getLocalPart());
            }
        }
        return result;
    }

    private String messageName(Input input) {
        if (input == null || input.getMessage() == null) {
            return "";
        }
        return input.getMessage().getQName().getLocalPart();
    }

    private String messageName(Output output) {
        if (output == null || output.getMessage() == null) {
            return "";
        }
        return output.getMessage().getQName().getLocalPart();
    }
}
