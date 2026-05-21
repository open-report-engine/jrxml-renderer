# JRXML Renderer

HTTP microservice for rendering JasperReports JRXML templates to PDF/XLSX.

## Usage

```bash
curl -X POST http://localhost:8080/api/render \
  -H "Content-Type: application/json" \
  -d '{
    "jrxml": "<?xml version=\"1.0\"?>...",
    "format": "pdf",
    "data_source": {
      "type": "sql",
      "url": "jdbc:clickhouse://host:8123/db?compress=0",
      "user": "default",
      "password": "",
      "query": "SELECT ..."
    },
    "parameters": {"period": "2026-Q1"}
  }' \
  -o report.pdf
```

## API

### POST /api/render

| Field | Type | Description |
|---|---|---|
| jrxml | string | JRXML template content |
| format | string | "pdf" or "xlsx" |
| data_source.type | string | "sql", "inline", "none" |
| data_source.url | string | JDBC URL |
| data_source.user | string | DB user |
| data_source.password | string | DB password |
| data_source.query | string | SQL query |
| parameters | object | JRXML parameters $P{...} |
| inline_data | array | JSON array for inline data |

### Auth

Set `AUTH_TOKEN` env variable to enable Bearer token authentication.

## Configuration

| Env | Default | Description |
|---|---|---|
| PORT | 8080 | HTTP port |
| AUTH_TOKEN | "" | Bearer token for auth (empty = disabled) |

## Build

```bash
mvn package -DskipTests
docker build -t jrxml-renderer .
docker run -p 8080:8080 jrxml-renderer
```
