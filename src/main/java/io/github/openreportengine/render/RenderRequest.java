package io.github.openreportengine.render;

import com.fasterxml.jackson.databind.JsonNode;

public class RenderRequest {
    public String jrxml;
    public String jasperBase64;
    public String format;
    public DataSourceConfig dataSource;
    public JsonNode parameters;
    public JsonNode inlineData;

    public static class DataSourceConfig {
        public String type; // sql, inline, none
        public String url;
        public String user;
        public String password;
        public String driver;
        public String query;

        public boolean isSql() {
            return "sql".equals(type);
        }

        public boolean isInline() {
            return "inline".equals(type);
        }
    }

    public static RenderRequest fromJson(JsonNode node) {
        RenderRequest req = new RenderRequest();

        JsonNode jrxmlNode = node.get("jrxml");
        JsonNode jasperNode = node.get("jasperBase64");
        if ((jrxmlNode == null || jrxmlNode.asText().isEmpty()) && (jasperNode == null || jasperNode.asText().isEmpty())) {
            throw new IllegalArgumentException("jrxml or jasperBase64 is required");
        }
        if (jrxmlNode != null) {
            req.jrxml = jrxmlNode.asText();
        }
        if (jasperNode != null) {
            req.jasperBase64 = jasperNode.asText();
        }

        req.format = node.has("format") ? node.get("format").asText("pdf") : "pdf";
        if (!req.format.equals("pdf") && !req.format.equals("xlsx") && !req.format.equals("docx") && !req.format.equals("csv")) {
            throw new IllegalArgumentException("format must be 'pdf', 'xlsx', 'docx' or 'csv'");
        }

        if (node.has("data_source")) {
            JsonNode ds = node.get("data_source");
            DataSourceConfig cfg = new DataSourceConfig();
            cfg.type = ds.has("type") ? ds.get("type").asText("sql") : "sql";
            cfg.url = ds.has("url") ? ds.get("url").asText() : null;
            cfg.user = ds.has("user") ? ds.get("user").asText() : null;
            cfg.password = ds.has("password") ? ds.get("password").asText() : null;
            cfg.driver = ds.has("driver") ? ds.get("driver").asText() : null;
            cfg.query = ds.has("query") ? ds.get("query").asText() : null;
            req.dataSource = cfg;
        }

        req.parameters = node.get("parameters");
        req.inlineData = node.get("inline_data");

        return req;
    }
}
