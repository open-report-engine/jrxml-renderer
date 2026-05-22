package io.github.openreportengine.render;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.openreportengine.datasource.DataSourceFactory;
import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.export.JRCsvExporter;
import net.sf.jasperreports.engine.export.ooxml.JRDocxExporter;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.functions.FunctionsBundle;
import net.sf.jasperreports.pdf.JRPdfExporter;

import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimpleWriterExporterOutput;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.util.*;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RenderService {

    static {
        System.setProperty("net.sf.jasperreports.awt.ignore.missing.font", "true");
        System.setProperty("net.sf.jasperreports.default.pdf.font.name", "Arial");
        System.setProperty("net.sf.jasperreports.default.pdf.encoding", "Identity-H");
        System.setProperty("net.sf.jasperreports.export.pdf.font.dir", "/app/");
        System.setProperty("net.sf.jasperreports.export.pdf.font.Arial", "/app/Arial.ttf");

        registerFontsFromDirs("/fonts/", "/app/");

        System.setProperty("net.sf.jasperreports.extension.registry.factory.fonts", "net.sf.jasperreports.engine.fonts.FontExtensionsRegistry");
        System.setProperty("net.sf.jasperreports.extension.fonts", "/app/fonts.xml,/app/arial-fonts.xml");

        Logger.getLogger("").setLevel(Level.OFF);
    }

    private static void registerFontsFromDirs(String... dirs) {
        for (String dir : dirs) {
            java.io.File fontDir = new java.io.File(dir);
            if (!fontDir.isDirectory()) continue;
            java.io.File[] ttfFiles = fontDir.listFiles((d, name) -> 
                name.toLowerCase().endsWith(".ttf") || name.toLowerCase().endsWith(".ttc") || name.toLowerCase().endsWith(".otf"));
            if (ttfFiles == null) continue;
            for (java.io.File f : ttfFiles) {
                registerFont(f);
            }
        }
    }

    private static void registerFont(java.io.File fontFile) {
        try {
            java.awt.Font awtFont = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, fontFile);
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(awtFont);

            com.lowagie.text.pdf.BaseFont.createFont(
                fontFile.getAbsolutePath(), "Identity-H", com.lowagie.text.pdf.BaseFont.EMBEDDED);
            com.lowagie.text.FontFactory.register(fontFile.getAbsolutePath());

            System.err.println("Font registered: " + awtFont.getFontName() + " (" + fontFile + ")");
        } catch (Exception e) {
            System.err.println("Font registration failed: " + fontFile + " - " + e.getMessage());
        }
    }

    public ByteArrayOutputStream render(RenderRequest request) throws Exception {
        try {
            JasperReportsContext ctx = DefaultJasperReportsContext.getInstance();

            String requestSql = null;
            JasperReport jasperReport;
            Map<String, Object> params = convertParameters(request.parameters);

            if (request.jasperBase64 != null && !request.jasperBase64.isEmpty()) {
                System.err.println("Detected pre-compiled JasperReport format (jasperBase64)");
                byte[] jasperBytes = Base64.getDecoder().decode(request.jasperBase64);
                jasperReport = (JasperReport) JRLoader.loadObject(ctx, new ByteArrayInputStream(jasperBytes));
                System.err.println("Report loaded: " + jasperReport.getName());
            } else {
                byte[] data = request.jrxml.getBytes("UTF-8");

                // Try FontAwareReportLoader first (DOM parser with Arial support)
                JasperDesign design = null;
                try {
                    FontAwareReportLoader fontLoader = new FontAwareReportLoader();
                    design = fontLoader.loadDesign(ctx, data);
                    System.err.println("FontAwareReportLoader: design loaded");
                } catch (Exception e) {
                    System.err.println("FontAwareReportLoader failed: " + e.getMessage());
                }

                // Fallback to LegacyXmlLoader if FontAware failed
                if (design == null) {
                    System.err.println("Falling back to LegacyXmlLoader");
                    String xmlStr = new String(data, "UTF-8");
                    xmlStr = xmlStr.replaceAll("<queryString[^>]*>.*?</queryString>", "");
                    xmlStr = xmlStr.replaceAll("<font[^>]*/>", "");
                    com.jaspersoft.jasperreports.legacy.xml.LegacyXmlLoader legacy = new com.jaspersoft.jasperreports.legacy.xml.LegacyXmlLoader();
                    java.util.Optional<JasperDesign> opt = legacy.loadReport(ctx, xmlStr.getBytes("UTF-8"));
                    if (opt.isPresent()) {
                        design = opt.get();
                    }
                }

                if (design == null) {
                    throw new JRException("Unable to load report: no loader accepted the format");
                }

                System.err.println("Design loaded: " + design.getName());

                // Extract SQL from original JRXML
                if (request.dataSource != null && request.dataSource.isSql()) {
                    try {
                        String xmlStr = new String(data, "UTF-8");
                        System.err.println("Searching SQL in XML (" + xmlStr.length() + " bytes)");
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "<queryString[^>]*>(.*?)</queryString>",
                            java.util.regex.Pattern.DOTALL).matcher(xmlStr);
                        if (m.find()) {
                            String raw = m.group(1).trim();
                            System.err.println("Found raw SQL: " + raw.substring(0, Math.min(raw.length(), 80)));
                            if (raw.startsWith("<![CDATA[")) {
                                raw = raw.substring(9, raw.length() - 3);
                            }
                            if (!raw.isEmpty()) {
                                requestSql = raw.trim();
                                System.err.println("Extracted SQL (" + requestSql.length() + " chars)");
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("SQL extraction error: " + e.getMessage());
                    }
                }

                SimpleJasperReportsContext functionCtx = new SimpleJasperReportsContext(ctx);
                registerFunctions(functionCtx);

                System.err.println("Compiling report...");
                jasperReport = JasperCompileManager.getInstance(functionCtx).compile(design);
                System.err.println("Report compiled successfully");
            }

            Connection connection = null;
            JRDataSource dataSource = null;

            if (request.dataSource != null && request.dataSource.isSql()) {
                DataSource ds = DataSourceFactory.create(request.dataSource);
                connection = ds.getConnection();

                String sql = requestSql;
                if (sql == null) {
                    net.sf.jasperreports.engine.JRQuery queryObj = jasperReport.getQuery();
                    if (queryObj != null) sql = queryObj.getText();
                }
                if (sql != null && !sql.isEmpty()) {
                    sql = substituteParameters(sql, params);
                    System.err.println("Executing SQL: " + sql.substring(0, Math.min(sql.length(), 100)));
                    java.sql.Statement stmt = connection.createStatement();
                    java.sql.ResultSet rs = stmt.executeQuery(sql);
                    dataSource = new net.sf.jasperreports.engine.JRResultSetDataSource(rs);
                }
            } else if (request.inlineData != null && request.inlineData.isArray()) {
                dataSource = new net.sf.jasperreports.engine.JRDataSource() {
                    int idx = -1;
                    public boolean next() { idx++; return idx < request.inlineData.size(); }
                    public Object getFieldValue(net.sf.jasperreports.engine.JRField field) {
                        com.fasterxml.jackson.databind.JsonNode row = request.inlineData.get(idx);
                        com.fasterxml.jackson.databind.JsonNode val = row.get(field.getName());
                        if (val == null) return null;
                        if (val.isTextual()) return val.asText();
                        if (val.isInt()) return val.asInt();
                        if (val.isLong()) return val.asLong();
                        if (val.isDouble()) return val.asDouble();
                        if (val.isBoolean()) return val.asBoolean();
                        return val.asText();
                    }
                };
            }

            JasperPrint jasperPrint;
            try {
                if (dataSource != null) {
                    jasperPrint = JasperFillManager.fillReport(jasperReport, params, dataSource);
                } else if (connection != null) {
                    jasperPrint = JasperFillManager.fillReport(jasperReport, params, connection);
                } else {
                    jasperPrint = JasperFillManager.fillReport(jasperReport, params, new JREmptyDataSource());
                }
            } finally {
                if (connection != null) connection.close();
            }

            System.err.println("Report filled, pages: " + jasperPrint.getPages().size());

            // Post-fill: ensure pdfFontName is Arial for fields still using default font.
            // This is a safety net — FontAwareReportLoader already set fonts in design,
            // but some Jasper versions may reset fonts during compilation.
            // We only touch pdfFontName (not fontName) to avoid breaking text metrics.
            for (net.sf.jasperreports.engine.JRPrintPage page : jasperPrint.getPages()) {
                for (net.sf.jasperreports.engine.JRPrintElement element : page.getElements()) {
                    if (element instanceof net.sf.jasperreports.engine.JRPrintText) {
                        net.sf.jasperreports.engine.JRPrintText text = (net.sf.jasperreports.engine.JRPrintText) element;
                        try {
                            java.lang.reflect.Field pdfField = text.getClass().getDeclaredField("pdfFontName");
                            pdfField.setAccessible(true);
                            Object cur = pdfField.get(text);
                            if (cur == null || "Helvetica".equals(cur) || "SansSerif".equals(cur) || "DejaVu Sans".equals(cur)) {
                                pdfField.set(text, "Arial");
                            }
                        } catch (Exception e) {
                            System.err.println("pdfFontName replace: " + e.getMessage());
                        }
                    }
                }
            }
            System.err.println("Post-fill pdfFontName check done");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            switch (request.format) {
                case "pdf":
                    JRPdfExporter pdfExporter = new JRPdfExporter();
                    pdfExporter.setExporterInput(new SimpleExporterInput(jasperPrint));
                    pdfExporter.setExporterOutput(new SimpleOutputStreamExporterOutput(baos));
                    pdfExporter.exportReport();
                    break;
                case "xlsx":
                    JRXlsxExporter xlsxExporter = new JRXlsxExporter();
                    xlsxExporter.setExporterInput(new SimpleExporterInput(jasperPrint));
                    xlsxExporter.setExporterOutput(new SimpleOutputStreamExporterOutput(baos));
                    xlsxExporter.exportReport();
                    break;
                case "docx":
                    JRDocxExporter docxExporter = new JRDocxExporter();
                    docxExporter.setExporterInput(new SimpleExporterInput(jasperPrint));
                    docxExporter.setExporterOutput(new SimpleOutputStreamExporterOutput(baos));
                    docxExporter.exportReport();
                    break;
                case "csv":
                    JRCsvExporter csvExporter = new JRCsvExporter();
                    csvExporter.setExporterInput(new SimpleExporterInput(jasperPrint));
                    csvExporter.setExporterOutput(new SimpleWriterExporterOutput(baos));
                    csvExporter.exportReport();
                    break;
            }
            return baos;
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            System.err.println("RENDER ERROR: " + sw.toString());
            throw e;
        }
    }

    private String substituteParameters(String sql, Map<String, Object> params) {
        if (params == null || params.isEmpty()) return sql;
        String result = sql;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String placeholder = "$P{" + entry.getKey() + "}";
            Object value = entry.getValue();
            String strValue;
            if (value == null) {
                strValue = "NULL";
            } else if (value instanceof Number) {
                strValue = value.toString();
            } else {
                strValue = "'" + value.toString().replace("'", "''") + "'";
            }
            result = result.replace(placeholder, strValue);
            // Also replace $P!{} syntax (inject directly without quotes)
            String injectPlaceholder = "$P!{" + entry.getKey() + "}";
            result = result.replace(injectPlaceholder, value != null ? value.toString() : "");
        }
        return result;
    }

    private void registerFunctions(SimpleJasperReportsContext ctx) {
        try {
            ctx.setProperty("net.sf.jasperreports.extension.registry.factory.functions",
                "net.sf.jasperreports.functions.FunctionsRegistryFactory");
            ctx.setProperty("net.sf.jasperreports.extension.functions.math",
                "net.sf.jasperreports.functions.standard.MathFunctions, net.sf.jasperreports.functions.standard.LogicalFunctions");
            ctx.setProperty("net.sf.jasperreports.extension.functions.datetime",
                "net.sf.jasperreports.functions.standard.DateTimeFunctions");
            ctx.setProperty("net.sf.jasperreports.extension.functions.text",
                "net.sf.jasperreports.functions.standard.TextFunctions");
            ctx.setProperty("net.sf.jasperreports.extension.functions.report",
                "net.sf.jasperreports.functions.standard.ReportFunctions");

            FunctionsBundle bundle = new FunctionsBundle();
            bundle.addFunctionClass("net.sf.jasperreports.functions.standard.MathFunctions");
            bundle.addFunctionClass("net.sf.jasperreports.functions.standard.LogicalFunctions");
            bundle.addFunctionClass("net.sf.jasperreports.functions.standard.DateTimeFunctions");
            bundle.addFunctionClass("net.sf.jasperreports.functions.standard.TextFunctions");
            bundle.addFunctionClass("net.sf.jasperreports.functions.standard.ReportFunctions");

            List<FunctionsBundle> bundles = new ArrayList<>();
            bundles.add(bundle);
            ctx.setExtensions(FunctionsBundle.class, bundles);

            System.err.println("Functions registered successfully");
        } catch (Exception e) {
            System.err.println("Failed to register functions: " + e.getMessage());
            e.printStackTrace(System.err);
        }
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
}
