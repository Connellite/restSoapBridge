# REST SOAP Bridge

Universal REST → SOAP bridge.  
The application accepts JSON over HTTP, invokes SOAP operations via Apache CXF, and returns JSON.

Licensed under the [Apache License, Version 2.0](LICENSE).

Swagger UI lists all available endpoints generated from WSDL/XSD.

## Requirements

- Java 17+
- Maven 3.9+
- Reachable SOAP backend (e.g. CXF at `http://host:8080/services/`)

## Build

```bash
mvn clean package -DskipTests
```

The build produces both executable JAR and deployable WAR artifacts:

```text
target/restSoapBridge-1.0-SNAPSHOT.jar
target/restSoapBridge-1.0-SNAPSHOT.war
```

## Run

```bash
java -jar target/restSoapBridge-1.0-SNAPSHOT.jar
```

After startup:

| URL | Description |
|-----|-------------|
| `http://localhost:9194/swagger-ui.html` | Swagger UI |
| `http://localhost:9194/v3/api-docs` | OpenAPI JSON |
| `http://localhost:9194/api/...` | Bridge REST endpoints |

Default port is `9194` (see `server.port`).

### Deploy as WAR

The WAR is intended for an external Jakarta Servlet container compatible with Spring Boot 3 / Spring Framework 6, for example Tomcat 10.1+.

Deploy:

```text
target/restSoapBridge-1.0-SNAPSHOT.war
```

If the file is deployed as `restSoapBridge-1.0-SNAPSHOT.war`, the default context path is usually:

```text
/restSoapBridge-1.0-SNAPSHOT
```

To deploy at the root context, rename the WAR to `ROOT.war` or configure the context path in the servlet container.

In WAR mode `server.port` is controlled by the external container, not by the application.

---

## Application settings

All settings are provided via `application.yml` or standard Spring Boot mechanisms (env vars, CLI args, external config files).

### Main properties

```yaml
server:
  port: 9194

bridge:
  auto:
    services-url: http://127.0.0.1:8080/services/
    path-prefix: /api
    http-method: POST

logging:
  level:
    io.github.connellite: DEBUG
```

### `bridge.auto.*`

| Property | Description | Example |
|----------|-------------|---------|
| `services-url` | URL of the CXF services listing page | `http://127.0.0.1:8080/services/` |
| `path-prefix` | REST path prefix | `/api` |
| `http-method` | HTTP method for all generated endpoints | `POST` |
| `fail-on-unavailable` | `true`: do not start if `services-url` or any `wsdl-*-url` is unavailable; `false`: log a warning and skip that source | `true` |

`path-prefix` must not be `/`: generated endpoints would shadow Swagger UI and actuator.

One endpoint is created per SOAP operation:

```text
{path-prefix}/{ServiceName}/{operationName}
```

Example: `POST /api/Auth/login`.

### Per-operation endpoints: `bridge.operations.*`

Path, HTTP method and presence of single endpoints, by service and operation name (case-insensitive):

```yaml
bridge:
  operations:
    Documents:
      getDocument:
        path: /documents/{uuid}   # below path-prefix: GET /api/documents/{uuid}
        method: GET
      findDocuments:
        path: /documents
        method: GET
      purge:
        enabled: false            # no endpoint
```

- `{name}` path variables fill the request field of that name (`{customer.id}` a nested one); a variable that is
  not a request field fails the start. Path variables win over the body.
- `GET` and `DELETE` take the other fields from the query string: `?token=t&version=3`, nested fields as
  `customer.name=Ann`, arrays as repeated parameters (`tags=a&tags=b`). Swagger shows them as query parameters;
  arrays of objects and maps have no query form, use `POST` for such operations.
- Two operations on the same method and path fail the start.

### Request validation: `bridge.validation.*`

Request bodies are validated against the JSON schema generated from XSD (the same one shown in Swagger)
before the SOAP call. A mismatch returns `400` with an `errors` list:

```json
{"title":"Invalid request body","status":400,"errors":["$.customer: required property 'name' not found","$.customer.age: string found, [integer, null] expected"]}
```

Types and required fields are checked; optional fields may be `null`; `format` (date/time) is not enforced.
XSD facets are checked as well: `xs:enumeration`, `xs:pattern`, length and numeric range facets
(`minInclusive`, `maxExclusive`, ...). Patterns that use XSD-only syntax (`\i`, `\c`, `\p{IsBlock}`, class subtraction)
are not published or checked.
Numbers and booleans may also be sent as strings (`"42"`, `"1.5"`, `"true"`): they are converted to the XSD type;
a string that does not look like a number/boolean (`"abc"`) is rejected.
Operations without XSD field metadata are not validated.

| Property | Description | Default |
|----------|-------------|---------|
| `enabled` | Turn validation on/off | `true` |
| `reject-unknown-fields` | Reject top-level fields the operation does not have (`400` with `$: allowed fields are [...]`), e.g. `uuid` sent instead of `folderUuid` | `true` |

### Backend authentication and headers: `bridge.backend.*`

```yaml
bridge:
  backend:
    basic-auth:
      username: bridge
      password: ${BACKEND_PASSWORD}
    ws-security:            # WS-Security UsernameToken in every SOAP request
      username: soap-user
      password: ${WSS_PASSWORD}
      password-type: digest # text | digest | none
      add-nonce: true
      add-created: true
    forward-headers:        # REST request headers copied to the SOAP HTTP request
      - Authorization
      - Accept-Language
```

Basic auth and WS-Security are independent and can be combined. Do not forward `Authorization`
when `basic-auth` is set: both would write the same header.

#### WSDL behind authentication

`basic-auth` is also used to load the services page, WSDL and XSD; `wsdl-basic-auth` sets other credentials for
these downloads only:

```yaml
bridge:
  backend:
    wsdl-basic-auth:
      username: wsdl-reader
      password: ${WSDL_PASSWORD}
```

The credentials are sent only in answer to an HTTP `401` challenge, and only to the scheme, host and port of
`services-url` and the `wsdl-*-url`s; WSDL links of a services page that point elsewhere and external XSDs
(e.g. `www.w3.org`) never get them. They are served by the JVM-wide `java.net.Authenticator` (CXF has no other
hook for its WSDL downloads), which delegates every other request to the previously installed one.

#### Endpoint address

The SOAP call goes to the `soap:address` of the WSDL. When the WSDL advertises a host the bridge cannot reach
(`localhost`, an internal name):

```yaml
bridge:
  backend:
    endpoint-base-url: https://soap-gateway:8443   # replaces scheme, host and port; the WSDL path is kept
    endpoint-addresses:                            # full address per service (name as in the REST path)
      Auth: http://auth-backend:9000/services/Auth
```

`endpoint-addresses` wins over `endpoint-base-url`. Timeouts, Basic auth and WS-Security apply to the new address.

### SOAP headers declared in the WSDL: `bridge.soap-headers.*`

Headers of the binding (`<soap:header message="..." part="..."/>`, also those of another message) are sent from
the `soapHeaders` request object, one property per header part:

```json
{"text": "hi", "soapHeaders": {"auth": {"token": "secret"}, "locale": "de"}}
```

Swagger and validation show them; a header that is not sent is left out of the envelope. The JSON field name is
`bridge.soap-headers.json-field` (default `soapHeaders`). Response headers are not mapped.

### Correlation id: `bridge.correlation.*`

Every REST request gets a correlation id: taken from the `X-Correlation-Id` header (if it is a safe token of up to 128 characters)
or generated as a UUID. The id is returned in the response header, put into the log MDC (`%X{correlationId}`,
already part of the default log pattern) and sent to the SOAP backend.

| Property | Description | Default |
|----------|-------------|---------|
| `header-name` | REST and backend HTTP header name | `X-Correlation-Id` |
| `forward-to-backend` | Send the id to the backend as an HTTP header | `true` |
| `soap-header.enabled` | Also add `<CorrelationId>` to the SOAP envelope header | `false` |
| `soap-header.namespace` | Namespace of the SOAP header element | `urn:rest-soap-bridge` |
| `soap-header.local-name` | Local name of the SOAP header element | `CorrelationId` |

### SOAP client pool: `bridge.client-pool.*`

Dynamic CXF clients (one per WSDL) are cached with Caffeine. Metrics are published as `cache.*{cache="soap.clients"}`.

| Property | Description | Default |
|----------|-------------|---------|
| `maximum-size` | Maximum number of cached clients | `100` |
| `expire-after-access` | Evict a client unused for this long (empty = never) | `1h` |
| `refresh-after-write` | Rebuild a client in the background (re-reads WSDL) this long after creation (empty = never) | — |

### JSON field names

JSON fields use XSD names, not Java names: JAXB annotations are honoured, so `return`, `class` or `item-id`
appear in JSON as in the WSDL. XSD dates are written in their lexical form (`2024-01-02T03:04:05+03:00`).

Swagger and validation follow the XSD shape JAXB binds to:

| XSD | JSON |
|-----|------|
| `complexContent/extension` (type inheritance) | base type fields, then the derived type fields |
| `xs:attribute`, `xs:attributeGroup` | ordinary properties next to the elements (`use="required"` → required) |
| `simpleContent` (text plus attributes) | `{"value": ..., "<attribute>": ...}` |
| named or anonymous `xs:simpleType` restriction | the built-in base type plus its facets (`enum`, `pattern`, ...) |
| `xs:list` | array of the item type |
| `xs:group ref` | the group's elements inline |
| `xs:choice` | all alternatives as optional fields |

`xs:any`, `xs:union` (published as a string) and substitution groups are not expanded.

### Version

The OpenAPI `info.version` is the Maven project version (from `META-INF/build-info.properties`, also exposed at
`/actuator/info`).

> Switching between the `spring-2` and `spring-3` profiles requires `mvn clean`: incremental compilation does not
> notice the changed preprocessor symbol.

---

## Environment variables

Spring Boot maps properties to UPPER_SNAKE_CASE automatically:

| Property | Environment variable |
|----------|---------------------|
| `server.port` | `SERVER_PORT` |
| `bridge.auto.services-url` | `BRIDGE_AUTO_SERVICES_URL` |
| `bridge.auto.path-prefix` | `BRIDGE_AUTO_PATH_PREFIX` |
| `bridge.auto.http-method` | `BRIDGE_AUTO_HTTP_METHOD` |
| `bridge.auto.wsdl-url` | `BRIDGE_AUTO_WSDL_URL` |
| `bridge.auto.wsdl-1-url`, `bridge.auto.wsdl-auth-url`, ... | `BRIDGE_AUTO_WSDL_1_URL`, `BRIDGE_AUTO_WSDL_AUTH_URL`, ... |

Example:

```bash
export SERVER_PORT=9194
export BRIDGE_AUTO_SERVICES_URL=http://soap-backend:8080/services/
export BRIDGE_AUTO_PATH_PREFIX=/api
export BRIDGE_AUTO_HTTP_METHOD=POST
java -jar restSoapBridge-1.0-SNAPSHOT.jar
```

---

## Configuration after build (outside the JAR)

### 1. Command-line arguments

```bash
java -jar restSoapBridge-1.0-SNAPSHOT.jar \
  --server.port=9194 \
  --bridge.auto.services-url=http://192.168.1.10:8080/services/ \
  --bridge.auto.path-prefix=/api \
  --bridge.auto.http-method=POST
```

### 2. External `application.yml`

Layout:

```text
/opt/bridge/
├── restSoapBridge-1.0-SNAPSHOT.jar
└── application.yml
```

`application.yml`:

```yaml
server:
  port: 9194

bridge:
  auto:
    services-url: http://192.168.1.10:8080/services/
    path-prefix: /api
    http-method: POST
```

Run from the same directory:

```bash
cd /opt/bridge
java -jar restSoapBridge-1.0-SNAPSHOT.jar
```

### 3. Explicit config directory

```bash
java -jar restSoapBridge-1.0-SNAPSHOT.jar \
  --spring.config.additional-location=file:./config/
```

---

## Configuration for WAR deployment

For WAR deployments, the application is started by the servlet container, so command-line arguments like `java -jar ... --bridge.auto.services-url=...` are not used directly. Use environment variables, JVM system properties, or servlet-container startup options.

### 1. Environment variables

Set variables before starting the container:

```bash
export BRIDGE_AUTO_SERVICES_URL=http://soap-backend:8080/services/
export BRIDGE_AUTO_PATH_PREFIX=/api
export BRIDGE_AUTO_HTTP_METHOD=POST
export SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/rest-soap-bridge/application.yml
```

Then deploy `restSoapBridge-1.0-SNAPSHOT.war` to the container.

### 2. JVM system properties

For Tomcat on Linux, add properties to `setenv.sh`:

```bash
export CATALINA_OPTS="$CATALINA_OPTS \
  -Dspring.config.additional-location=file:/etc/rest-soap-bridge/application.yml \
  -Dbridge.auto.services-url=http://soap-backend:8080/services/ \
  -Dbridge.auto.path-prefix=/api \
  -Dbridge.auto.http-method=POST"
```

On Windows, use `setenv.bat`:

```bat
set "CATALINA_OPTS=%CATALINA_OPTS% -Dspring.config.additional-location=file:C:/rest-soap-bridge/application.yml"
set "CATALINA_OPTS=%CATALINA_OPTS% -Dbridge.auto.services-url=http://soap-backend:8080/services/"
set "CATALINA_OPTS=%CATALINA_OPTS% -Dbridge.auto.path-prefix=/api"
set "CATALINA_OPTS=%CATALINA_OPTS% -Dbridge.auto.http-method=POST"
```

### 3. External `application.yml`

Recommended layout:

```text
/etc/rest-soap-bridge/
└── application.yml

$CATALINA_BASE/webapps/
└── restSoapBridge.war
```

`/etc/rest-soap-bridge/application.yml`:

```yaml
bridge:
  auto:
    services-url: http://soap-backend:8080/services/
    # services-url can be empty when only individual WSDL URLs are used
    # wsdl-url: http://soap-backend:8080/services/Auth?wsdl
    # wsdl-1-url: http://soap-backend:8080/services/ResultList?wsdl
    path-prefix: /api
    http-method: POST

logging:
  level:
    io.github.connellite: INFO
```

Pass the config location through the container:

```bash
-Dspring.config.additional-location=file:/etc/rest-soap-bridge/application.yml
```

### 4. URLs in WAR mode

If the WAR is deployed under context path `/restSoapBridge`, URLs include that prefix:

| URL | Description |
|-----|-------------|
| `http://host:8080/restSoapBridge/swagger-ui.html` | Swagger UI |
| `http://host:8080/restSoapBridge/v3/api-docs` | OpenAPI JSON |
| `http://host:8080/restSoapBridge/api/...` | Bridge REST endpoints |

If deployed as `ROOT.war`, URLs are the same as in JAR mode, except the port belongs to the container.

---

## How auto-generation works

On startup the bridge:

1. Loads the HTML page at `bridge.auto.services-url`
2. Discovers WSDL links (CXF services page)
3. Parses WSDL and XSD
4. Builds request/response mappings in memory

Example generated mapping:

```yaml
path: /api/Auth/login
method: POST
request:
  "$.user": "$.login.user"
response:
  "#root.return": "$.return"
```

---

## Example request

```bash
curl -X POST http://localhost:9194/api/Auth/login \
  -H "Content-Type: application/json" \
  -d '{"user":"admin","pass":"secret"}'
```

The response depends on WSDL/XSD. For a typical `login`:

```json
{"return":"<session-id>"}
```

---

## Common errors

Errors are RFC 7807 problems (`application/problem+json`) with a `correlationId`. The status says whose problem it is:

| HTTP | Cause |
|------|-------|
| 400 | Request body does not match the operation schema, unknown field, or a value that cannot become the SOAP type (`errors` list) |
| 401 | SOAP fault `AccessDeniedException` (see `bridge.errors.fault-status`) |
| 404 | Endpoint not generated (operation missing in WSDL or different `path-prefix`) |
| 500 | Bug in the bridge itself (e.g. `NullPointerException`); details only in the server log under the same `correlationId` |
| 502 | SOAP fault from the backend (`faultName`, `faultCode`, `faultSubcodes`, `faultInfo`), backend HTTP error, unreadable response, or response that does not match the generated mapping |
| 503 | Backend not reachable (connection refused, unknown host, WSDL cannot be loaded) or backend answered 503; also no mappings loaded |
| 504 | Backend did not answer within `bridge.auto.receive-timeout` |

`faultName` is the declared WSDL fault (`AccessDeniedException` for class `AccessDeniedException_Exception`) or `SoapFault`
for undeclared ones. `faultCode` is only known for undeclared faults: CXF rebuilds declared ones from the fault bean
(`faultInfo`). Map fault names to statuses:

```yaml
bridge:
  errors:
    fault-status:
      AccessDeniedException: 401
      NotFoundException: 404
      ConflictException: 409
```

Unlisted faults give 502; a non-standard status code falls back to 502.

---

## Recommended production layout

```text
/etc/rest-soap-bridge/
└── application.yml

/var/lib/rest-soap-bridge/
└── restSoapBridge-1.0-SNAPSHOT.jar
```

Run:

```bash
java -jar /var/lib/rest-soap-bridge/restSoapBridge-1.0-SNAPSHOT.jar \
  --spring.config.additional-location=file:/etc/rest-soap-bridge/application.yml
```
