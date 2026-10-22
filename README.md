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

`path-prefix` must not be `/`: generated endpoints would shadow Swagger UI and actuator.

One endpoint is created per SOAP operation:

```text
{path-prefix}/{ServiceName}/{operationName}
```

Example: `POST /api/Auth/login`.

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

| HTTP | Cause |
|------|-------|
| 404 | Endpoint not generated (operation missing in WSDL or different `path-prefix`) |
| 401 | SOAP `AccessDeniedException` |
| 502 | SOAP error, WSDL/XSD parsing failure, or backend unreachable |

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
