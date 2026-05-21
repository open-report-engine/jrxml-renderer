package io.github.openreportengine.render;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.openreportengine.datasource.DataSourceFactory;
import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.data.JsonDataSource;
import net.sf.jasperreports.engine.export.JRPdfExporter;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class RenderService {

    static {
        JRPropertiesUtil.getInstance(DefaultJasperReportsContext.getInstance())
            .setProperty("net.sf.jasperreports.awt.ignore.missing.font", "true");
    }

    public ByteArrayOutputStream render(RenderRequest request) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(request.jrxml.getBytes("UTF-8"));
        JasperReport jasperReport = JasperCompileManager.compileReport(bais);

        Map<String, Object> params = convertParameters(request.parameters);

        Connection connection = null;
        JRDataSource jrDataSource = null;

        if (request.dataSource != null && request.dataSource.isSql()) {
            DataSource ds = DataSourceFactory.create(request.dataSource);
            connection = ds.getConnection();
        } else if (request.dataSource != null && request.dataSource.isInline()) {
            jrDataSource = createInlineDataSource(request.inlineData);
        }

        JasperPrint jasperPrint;
        try {
            if (connection != null) {
                jasperPrint = JasperFillManager.fillReport(jasperReport, params, connection);
            } else if (jrDataSource != null) {
                jasperPrint = JasperFillManager.fillReport(jasperReport, params, jrDataSource);
            } else {
                jasperPrint = JasperFillManager.fillReport(jasperReport, params, new JREmptyDataSource());
            }
        } finally {
            if (connection != null) connection.close();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        if (request.format.equals("pdf")) {
            JRPdfExporter exporter = new JRPdfExporter();
            exporter.setExporterInput(new SimpleExporterInput(jasperPrint));
            exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(baos));
            exporter.exportReport();
        } else {
            JRXlsxExporter exporter = new JRXlsxExporter();
            exporter.setExporterInput(new SimpleExporterInput(jasperPrint));
            exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(baos));
            exporter.exportReport();
        }

        return baos;
    }

    private Map<String, Object> convertParameters(JsonNode params) {
        Map<String, Object> result = new HashMap<>();
        if (params == null) return result;
        Iterator<Map.Entry<String, JsonNode>> fields = params.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode val = entry.getValue();
            if (val.isTextual()) result.put(entry.getKey(), val.asText());
            else if (val.isInt()) result.put(entry.getKey(), val.asInt());
            else if (val.isLong()) result.put(entry.getKey(), val.asLong());
            else if (val.isDouble()) result.put(entry.getKey(), val.asDouble());
            else if (val.isBoolean()) result.put(entry.getKey(), val.asBoolean());
            else result.put(entry.getKey(), val.asText());
        }
        return result;
    }

    private JRDataSource createInlineDataSource(JsonNode inlineData) throws Exception {
        if (inlineData == null) return new JREmptyDataSource();
        return new JsonDataSource(new ByteArrayInputStream(inlineData.toString().getBytes("UTF-8")));
    }
}
