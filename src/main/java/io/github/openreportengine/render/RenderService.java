package io.github.openreportengine.render;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.openreportengine.datasource.DataSourceFactory;
import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.design.*;
import net.sf.jasperreports.engine.type.BandTypeEnum;
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
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;
import java.util.*;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RenderService {

    static {
        System.setProperty("net.sf.jasperreports.awt.ignore.missing.font", "true");
        System.setProperty("net.sf.jasperreports.default.pdf.font.name", FontDefaults.FAMILY);
        System.setProperty("net.sf.jasperreports.default.pdf.encoding", FontDefaults.PDF_ENCODING);
        System.setProperty("net.sf.jasperreports.export.pdf.font.dir", "/app/");
        System.setProperty("net.sf.jasperreports.export.pdf.font." + FontDefaults.FAMILY, "/app/DejaVuSansMono.ttf");

        // Register all fonts from /fonts/ and /app/ directories
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
                    fixOrigins(design);
                    // Force-add summary elements from raw XML if summary band is empty
                    forceSummaryFromXml(design, data);
                    System.err.println("FontAwareReportLoader: design loaded");
                } catch (Exception e) {
                    System.err.println("FontAwareReportLoader failed: " + e.getMessage());
                }

                // Fallback to LegacyXmlLoader if FontAware failed
                if (design == null) {
                    System.err.println("Falling back to LegacyXmlLoader");
                    String xmlStr = new String(data, "UTF-8");
                    xmlStr = xmlStr.replaceAll("<queryString[^>]*>.*?</queryString>", "");
                    com.jaspersoft.jasperreports.legacy.xml.LegacyXmlLoader legacy = new com.jaspersoft.jasperreports.legacy.xml.LegacyXmlLoader();
                    java.util.Optional<JasperDesign> opt = legacy.loadReport(ctx, xmlStr.getBytes("UTF-8"));
                    if (opt.isPresent()) {
                        design = opt.get();
                        fixOrigins(design);
                    }
                }

                if (design == null) {
                    throw new JRException("Unable to load report: no loader accepted the format");
                }

                System.err.println("Design loaded: " + design.getName());

                // Collect all data sources
                List<RenderRequest.DataSourceConfig> allDataSources = new ArrayList<>();
                if (request.dataSource != null) {
                    allDataSources.add(request.dataSource);
                }
                if (request.dataSources != null) {
                    allDataSources.addAll(request.dataSources);
                }

                // Extract SQL from original JRXML (for backward compat)
                if (!allDataSources.isEmpty()) {
                    try {
                        String xmlStr = new String(data, "UTF-8");
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "<queryString[^>]*>(.*?)</queryString>",
                            java.util.regex.Pattern.DOTALL).matcher(xmlStr);
                        if (m.find()) {
                            String raw = m.group(1).trim();
                            if (raw.startsWith("<![CDATA[")) {
                                raw = raw.substring(9, raw.length() - 3);
                            }
                            if (!raw.isEmpty()) {
                                requestSql = raw.trim();
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

            // Handle multiple data sources
            List<RenderRequest.DataSourceConfig> allDataSources = new ArrayList<>();
            if (request.dataSource != null) {
                allDataSources.add(request.dataSource);
            }
            if (request.dataSources != null) {
                allDataSources.addAll(request.dataSources);
            }

            if (!allDataSources.isEmpty()) {
                // Use the first SQL data source as the main data source for JasperReports
                RenderRequest.DataSourceConfig mainSource = allDataSources.get(0);
                if (mainSource.isSql()) {
                    DataSource ds = DataSourceFactory.create(mainSource);
                    connection = ds.getConnection();

                    // Execute all extra queries and inject results as parameters
                    for (int i = 1; i < allDataSources.size(); i++) {
                        RenderRequest.DataSourceConfig extraSource = allDataSources.get(i);
                        if (extraSource.isSql() && extraSource.query != null) {
                            executeExtraQuery(extraSource, params, connection);
                        }
                    }

                    // Execute main query
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

            // Post-fill: ensure fontName and pdfFontName are set to DejaVu Sans Mono.
            // This ensures staticText elements also get proper Cyrillic-capable font.
            for (net.sf.jasperreports.engine.JRPrintPage page : jasperPrint.getPages()) {
                for (net.sf.jasperreports.engine.JRPrintElement element : page.getElements()) {
                    if (element instanceof net.sf.jasperreports.engine.JRPrintText) {
                        net.sf.jasperreports.engine.JRPrintText text = (net.sf.jasperreports.engine.JRPrintText) element;
                        String fn = text.getOwnFontName();
                        System.err.println("  element fontName='" + fn + "' pdf='" + text.getOwnPdfFontName() + "' enc='" + text.getOwnPdfEncoding() + "'");
                        if (fn == null || fn.isEmpty() || "Helvetica".equals(fn) || "SansSerif".equals(fn) || "DejaVu Sans".equals(fn) || "Arial".equals(fn)) {
                            text.setFontName(FontDefaults.FAMILY);
                        }
                        String pdf = text.getOwnPdfFontName();
                        if (pdf == null || "Helvetica".equals(pdf) || "SansSerif".equals(pdf) || "DejaVu Sans".equals(pdf) || "Arial".equals(pdf)) {
                            text.setPdfFontName(FontDefaults.PDF_NAME);
                        }
                        String enc = text.getOwnPdfEncoding();
                        if (enc == null || "CP1252".equals(enc) || "WinAnsiEncoding".equals(enc) || "".equals(enc)) {
                            text.setPdfEncoding("Identity-H");
                        }
                    }
                }
            }
            System.err.println("Post-fill font replacement done");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            switch (request.format) {
                case "pdf":
                    JRPdfExporter pdfExporter = new JRPdfExporter();
                    pdfExporter.setExporterInput(new SimpleExporterInput(jasperPrint));
                    pdfExporter.setExporterOutput(new SimpleOutputStreamExporterOutput(baos));
                    pdfExporter.exportReport();

                    // Post-process: balance q/Q operators in PDF content streams.
                    // JasperReports/iText sometimes produces unbalanced graphics state
                    // operators, which causes Adobe Acrobat to reject the file.
                    {
                        byte[] raw = baos.toByteArray();
                        byte[] fixed = balanceQOperators(raw);
                        baos.reset();
                        baos.write(fixed);
                    }
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

    private void forceSummaryFromXml(JasperDesign design, byte[] data) {
        try {
            String xml = new String(data, "UTF-8");
            // Find <summary> section in raw XML
            int summaryStart = xml.indexOf("<summary>");
            int summaryEnd = xml.indexOf("</summary>");
            if (summaryStart < 0 || summaryEnd < 0) return;
            
            String summaryXml = xml.substring(summaryStart, summaryEnd + 10);
            // Extract summary elements using LegacyXmlLoader for just the summary
            // Parse the summary section separately
            String wrapper = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<jasperReport name=\"_summary_extract\" pageWidth=\"1191\" pageHeight=\"842\" whenNoDataType=\"AllSectionsNoDetail\">\n" +
                "<field name=\"_dummy\" class=\"java.lang.String\"/>\n" +
                summaryXml + "\n</jasperReport>";
            
            com.jaspersoft.jasperreports.legacy.xml.LegacyXmlLoader legacy = new com.jaspersoft.jasperreports.legacy.xml.LegacyXmlLoader();
            java.util.Optional<JasperDesign> summaryDesign = legacy.loadReport(
                DefaultJasperReportsContext.getInstance(), wrapper.getBytes("UTF-8"));
            if (summaryDesign.isPresent()) {
                java.lang.reflect.Method getSummary = summaryDesign.get().getClass().getMethod("getSummary");
                JRDesignBand srcBand = (JRDesignBand) getSummary.invoke(summaryDesign.get());
                if (srcBand != null && srcBand.getElements().length > 0) {
                    java.lang.reflect.Method getTargetSummary = design.getClass().getMethod("getSummary");
                    JRDesignBand targetBand = (JRDesignBand) getTargetSummary.invoke(design);
                    if (targetBand != null) {
                        targetBand.setHeight(srcBand.getHeight());
                        for (JRElement elem : srcBand.getElements()) {
                            targetBand.addElement(elem);
                        }
                        System.err.println("forceSummary: added " + srcBand.getElements().length + " elements, height=" + srcBand.getHeight());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("forceSummary: " + e.getMessage());
        }
    }

    private void fixOrigins(JasperDesign design) {
        // Try to fix summary band height from original XML (not available from parsed design)
        // This is needed because JRXmlLoader (Jackson) may not set summary band height properly
        try {
            java.lang.reflect.Method getSummary = design.getClass().getMethod("getSummary");
            JRDesignBand sb = (JRDesignBand) getSummary.invoke(design);
            if (sb != null && sb.getHeight() == 0) {
                sb.setHeight(520);
                System.err.println("fixOrigins: forced summary height to 520");
            }
        } catch (Exception e) {
            System.err.println("fixSummaryHeight: " + e.getMessage());
        }
        try {
            java.lang.reflect.Method getDetail = design.getClass().getMethod("getDetailSection");
            Object section = getDetail.invoke(design);
            if (section != null) {
                java.lang.reflect.Method getBands = section.getClass().getMethod("getBands");
                JRBand[] bands = (JRBand[]) getBands.invoke(section);
                if (bands != null) {
                    JROrigin origin = new JROrigin(net.sf.jasperreports.engine.type.BandTypeEnum.DETAIL);
                    for (JRBand band : bands) {
                        if (band instanceof JRDesignBand) {
                            java.lang.reflect.Method setOrigin = JRDesignBand.class.getDeclaredMethod("setOrigin", JROrigin.class);
                            setOrigin.setAccessible(true);
                            setOrigin.invoke(band, origin);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("fixDetailOrigin: " + e.getMessage());
        }
        try {
            java.lang.reflect.Method getSummary = design.getClass().getMethod("getSummary");
            JRDesignBand sb = (JRDesignBand) getSummary.invoke(design);
            if (sb != null) {
                java.lang.reflect.Method setOrigin = JRDesignBand.class.getDeclaredMethod("setOrigin", JROrigin.class);
                setOrigin.setAccessible(true);
                setOrigin.invoke(sb, new JROrigin(net.sf.jasperreports.engine.type.BandTypeEnum.SUMMARY));
            }
        } catch (Exception e) {}
        try {
            java.lang.reflect.Method getCH = design.getClass().getMethod("getColumnHeader");
            JRDesignBand chb = (JRDesignBand) getCH.invoke(design);
            if (chb != null) {
                java.lang.reflect.Method setOrigin = JRDesignBand.class.getDeclaredMethod("setOrigin", JROrigin.class);
                setOrigin.setAccessible(true);
                setOrigin.invoke(chb, new JROrigin(net.sf.jasperreports.engine.type.BandTypeEnum.COLUMN_HEADER));
            }
        } catch (Exception e) {}
        try {
            java.lang.reflect.Method getTitle = design.getClass().getMethod("getTitle");
            JRDesignBand tb = (JRDesignBand) getTitle.invoke(design);
            if (tb != null) {
                java.lang.reflect.Method setOrigin = JRDesignBand.class.getDeclaredMethod("setOrigin", JROrigin.class);
                setOrigin.setAccessible(true);
                setOrigin.invoke(tb, new JROrigin(net.sf.jasperreports.engine.type.BandTypeEnum.TITLE));
            }
        } catch (Exception e) {}
        System.err.println("fixOrigins applied to all bands");
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

    private void executeExtraQuery(RenderRequest.DataSourceConfig source, Map<String, Object> params, Connection existingConn) throws Exception {
        if (source.query == null) return;
        String sql = substituteParameters(source.query, params);
        System.err.println("Executing extra query: " + sql.substring(0, Math.min(sql.length(), 100)));
        java.sql.Statement stmt = existingConn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(sql);
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        if (rs.next()) {
            for (int i = 1; i <= cols; i++) {
                String colName = meta.getColumnName(i);
                Object val = rs.getObject(i);
                if (val != null && !params.containsKey(colName)) {
                    params.put(colName, val);
                    System.err.println("  param " + colName + " = " + val);
                }
            }
        }
        rs.close();
        stmt.close();
    }

    private byte[] balanceQOperators(byte[] pdfBytes) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pdfBytes.length + 100);
        int pos = 0;

        java.util.regex.Pattern streamRe = java.util.regex.Pattern.compile(
            "stream\\n(.+?)\\nendstream", java.util.regex.Pattern.DOTALL);
        String text = new String(pdfBytes, "ISO-8859-1");
        java.util.regex.Matcher m = streamRe.matcher(text);

        while (m.find()) {
            out.write(pdfBytes, pos, m.start(1) - pos - 7); // -7 for "stream\n"
            out.write("stream\n".getBytes("ISO-8859-1"));

            String rawContent = m.group(1);
            byte[] rawBytes = rawContent.getBytes("ISO-8859-1");
            byte[] decompressed;

            try {
                java.util.zip.Inflater inflater = new java.util.zip.Inflater();
                inflater.setInput(rawBytes);
                ByteArrayOutputStream baos = new ByteArrayOutputStream(rawBytes.length * 2);
                byte[] buf = new byte[4096];
                int len;
                while ((len = inflater.inflate(buf)) > 0) {
                    baos.write(buf, 0, len);
                }
                inflater.end();
                decompressed = baos.toByteArray();
            } catch (java.util.zip.DataFormatException e) {
                // Not a compressed stream, write as-is
                out.write(rawBytes);
                out.write("\nendstream".getBytes("ISO-8859-1"));
                pos = m.end();
                continue;
            }

            String decText = new String(decompressed, "ISO-8859-1");
            String balanced;

            if (decText.contains("BT")) {
                balanced = balanceContent(decText);
            } else {
                balanced = decText;
            }

            byte[] balancedBytes = balanced.getBytes("ISO-8859-1");
            java.util.zip.Deflater deflater = new java.util.zip.Deflater();
            deflater.setInput(balancedBytes);
            deflater.finish();
            ByteArrayOutputStream compressedOut = new ByteArrayOutputStream(balancedBytes.length);
            byte[] buf = new byte[4096];
            while (!deflater.finished()) {
                int len = deflater.deflate(buf);
                compressedOut.write(buf, 0, len);
            }
            deflater.end();
            byte[] compressed = compressedOut.toByteArray();

            out.write(compressed);
            out.write("\nendstream".getBytes("ISO-8859-1"));
            pos = m.end();
        }

        if (pos < pdfBytes.length) {
            out.write(pdfBytes, pos, pdfBytes.length - pos);
        }

        return out.toByteArray();
    }

    private String balanceContent(String content) {
        int qCount = 0;
        int QCount = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == 'q' && (i == 0 || content.charAt(i - 1) <= ' ')) {
                qCount++;
            } else if (c == 'Q' && (i == 0 || content.charAt(i - 1) <= ' ')) {
                QCount++;
            }
        }
        if (qCount == QCount) return content;
        StringBuilder sb = new StringBuilder(content);
        if (qCount > QCount) {
            for (int i = 0; i < qCount - QCount; i++) {
                sb.append('\n').append('Q');
            }
        } else {
            for (int i = 0; i < QCount - qCount; i++) {
                sb.insert(0, "q\n");
            }
        }
        return sb.toString();
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
