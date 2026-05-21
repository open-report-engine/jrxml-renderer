package io.github.openreportengine.render;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.openreportengine.datasource.DataSourceFactory;
import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.export.JRCsvExporter;
import net.sf.jasperreports.engine.export.ooxml.JRDocxExporter;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.engine.xml.ReportLoader;
import net.sf.jasperreports.functions.FunctionsBundle;
import net.sf.jasperreports.pdf.JRPdfExporter;
import net.sf.jasperreports.poi.export.JRXlsExporter;
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
        // Don't force embed - rely on system font
        System.setProperty("net.sf.jasperreports.export.pdf.font.dir", "/app/");
        System.setProperty("net.sf.jasperreports.export.pdf.font.Arial", "/app/Arial.ttf");
        
        // Register all fonts from /fonts/ and /app/ directories
        registerFontsFromDirs("/fonts/", "/app/");

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
            // Register as AWT font
            java.awt.Font awtFont = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, fontFile);
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(awtFont);
            
            // Register for iText PDF
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

            JasperReport jasperReport;
            Map<String, Object> params = convertParameters(request.parameters);

            if (request.jasperBase64 != null && !request.jasperBase64.isEmpty()) {
                System.err.println("Detected pre-compiled JasperReport format (jasperBase64)");
                byte[] jasperBytes = Base64.getDecoder().decode(request.jasperBase64);
                jasperReport = (JasperReport) JRLoader.loadObject(ctx, new ByteArrayInputStream(jasperBytes));
                System.err.println("Report loaded: " + jasperReport.getName());
            } else {
                byte[] data = request.jrxml.getBytes("UTF-8");
                ServiceLoader<ReportLoader> loader = ServiceLoader.load(ReportLoader.class);
                JasperDesign design = null;
                int count = 0;

                for (ReportLoader reportLoader : loader) {
                    count++;
                    System.err.println("Trying ReportLoader: " + reportLoader.getClass().getName());
                    Optional<JasperDesign> opt = reportLoader.loadReport(ctx, data);
                    if (opt.isPresent()) {
                        design = opt.get();
                        System.err.println("  -> SUCCESS");
                        break;
                    }
                    System.err.println("  -> EMPTY");
                }

                System.err.println("ReportLoaders tried: " + count);

                if (design == null) {
                    throw new JRException("Unable to load report: no ReportLoader accepted the format");
                }

                System.err.println("Design loaded: " + design.getName());

                SimpleJasperReportsContext functionCtx = new SimpleJasperReportsContext(ctx);
                registerFunctions(functionCtx);

                System.err.println("Compiling report...");
                jasperReport = JasperCompileManager.getInstance(functionCtx).compile(design);
                System.err.println("Report compiled successfully");
            }

            Connection connection = null;
            if (request.dataSource != null && request.dataSource.isSql()) {
                DataSource ds = DataSourceFactory.create(request.dataSource);
                connection = ds.getConnection();
            }

            JasperPrint jasperPrint;
            try {
                if (connection != null) {
                    jasperPrint = JasperFillManager.fillReport(jasperReport, params, connection);
                } else {
                    jasperPrint = JasperFillManager.fillReport(jasperReport, params, new JREmptyDataSource());
                }
            } finally {
                if (connection != null) connection.close();
            }

            System.err.println("Report filled, pages: " + jasperPrint.getPages().size());

            // Replace font on all print elements
            for (net.sf.jasperreports.engine.JRPrintPage page : jasperPrint.getPages()) {
                for (net.sf.jasperreports.engine.JRPrintElement element : page.getElements()) {
                    if (element instanceof net.sf.jasperreports.engine.JRPrintText) {
                        net.sf.jasperreports.engine.JRPrintText text = (net.sf.jasperreports.engine.JRPrintText) element;
                        if (text.getFontName() == null || "Helvetica".equals(text.getFontName())) {
                            try {
                                java.lang.reflect.Field nameField = text.getClass().getDeclaredField("fontName");
                                nameField.setAccessible(true);
                                nameField.set(text, "DejaVu Sans");
                            } catch (Exception e) {
                                System.err.println("Font name replace error: " + e.getMessage());
                            }
                            try {
                                java.lang.reflect.Field pdfField = text.getClass().getDeclaredField("pdfFontName");
                                pdfField.setAccessible(true);
                                pdfField.set(text, "DejaVu Sans");
                            } catch (Exception e) {
                                System.err.println("PdfFont name replace error: " + e.getMessage());
                            }
                        }
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            switch (request.format) {
                case "pdf":
                    JRPdfExporter pdfExporter = new JRPdfExporter();
                    pdfExporter.setExporterInput(new SimpleExporterInput(jasperPrint));
                    pdfExporter.setExporterOutput(new SimpleOutputStreamExporterOutput(baos));
                    pdfExporter.exportReport();
                    break;
                case "xlsx":
                    JRXlsExporter xlsxExporter = new JRXlsExporter();
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
