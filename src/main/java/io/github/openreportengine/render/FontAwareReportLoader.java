package io.github.openreportengine.render;

import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.design.*;
import net.sf.jasperreports.engine.xml.JRXmlLoader;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.io.ByteArrayInputStream;
import java.util.*;

public class FontAwareReportLoader {

    private final List<JRDesignBand> detailBands = new ArrayList<>();

    public JasperDesign loadDesign(JasperReportsContext ctx, byte[] data) throws Exception {
        // Step 0: Configure Jackson XmlMapper to ignore unknown properties.
        // JacksonReportLoader (via JacksonUtil) uses XmlMapper to deserialize JRXML.
        // Fields like "height" on sections (JRDesignSection), "class" on fields,
        // "variableExpression" on variables cause failures if not ignored.
        // We pre-set the mapper in context so JacksonUtil.getXmlMapper() returns it.
        XmlMapper xmlMapper = new XmlMapper();
        xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        xmlMapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        xmlMapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        // Register custom serializers/deserializers from JasperReports
        com.fasterxml.jackson.databind.module.SimpleModule module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addDeserializer(java.awt.Color.class, new net.sf.jasperreports.jackson.util.ColorDeserializer());
        module.addSerializer(java.awt.Color.class, new net.sf.jasperreports.jackson.util.ColorSerializer());
        xmlMapper.registerModule(module);
        // Register component subtypes from context
        try {
            net.sf.jasperreports.engine.component.ComponentsEnvironment componentsEnv =
                net.sf.jasperreports.engine.component.ComponentsEnvironment.getInstance(ctx);
            for (net.sf.jasperreports.engine.component.ComponentsBundle bundle : componentsEnv.getBundles()) {
                for (Class<? extends net.sf.jasperreports.engine.component.Component> ct : bundle.getComponentTypes()) {
                    xmlMapper.registerSubtypes(ct);
                }
            }
        } catch (Exception e) {
            System.err.println("component registration: " + e.getMessage());
        }
        ctx.setValue("net.sf.jasperreports.jackson.xml.mapper", xmlMapper);

        // Step 1: Parse JRXML using the standard JasperReports JRXmlLoader.
        JasperDesign design;
        try {
            design = JRXmlLoader.load(ctx, new ByteArrayInputStream(data));
            System.err.println("JRXmlLoader: parsed " + design.getName());
        } catch (Exception e) {
            System.err.println("JRXmlLoader failed: " + e.getMessage());
            throw e;
        }

        // Step 2: Apply font fallback — replace default/Helvetica with DejaVu Sans Mono
        applyArialFallback(design);

        System.err.println("FontAwareReportLoader: fonts applied to " + design.getName());
        return design;
    }

    private void applyArialFallback(JasperDesign design) {
        List<JRDesignBand> bands = new ArrayList<>();
        try { bands.add((JRDesignBand) getDesignBand(design, "title")); } catch (Exception e) {}
        try { bands.add((JRDesignBand) getDesignBand(design, "pageHeader")); } catch (Exception e) {}
        try { bands.add((JRDesignBand) getDesignBand(design, "columnHeader")); } catch (Exception e) {}
        try { bands.add((JRDesignBand) getDesignBand(design, "columnFooter")); } catch (Exception e) {}
        try { bands.add((JRDesignBand) getDesignBand(design, "pageFooter")); } catch (Exception e) {}
        try { bands.add((JRDesignBand) getDesignBand(design, "summary")); } catch (Exception e) {}
        try { bands.add((JRDesignBand) getDesignBand(design, "background")); } catch (Exception e) {}

        for (JRDesignBand band : bands) {
            if (band == null) continue;
            applyArialToBand(band);
        }
        for (JRDesignBand band : detailBands) {
            applyArialToBand(band);
        }

        // Also process detail section bands
        try {
            java.lang.reflect.Method getDetail = design.getClass().getMethod("getDetailSection");
            Object section = getDetail.invoke(design);
            if (section != null) {
                java.lang.reflect.Method getBands = section.getClass().getMethod("getBands");
                JRBand[] detailBandsArray = (JRBand[]) getBands.invoke(section);
                if (detailBandsArray != null) {
                    for (JRBand b : detailBandsArray) {
                        applyArialToBand((JRDesignBand) b);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("detail bands font fallback: " + e.getMessage());
        }
    }

    private void applyArialToBand(JRDesignBand band) {
        if (band == null) return;
        for (JRElement elem : band.getElements()) {
            if (!(elem instanceof JRDesignTextElement)) continue;
            JRDesignTextElement te = (JRDesignTextElement) elem;
            String fn = te.getFontName();
            if (fn == null || fn.isEmpty() ||
                "Helvetica".equals(fn) || "SansSerif".equals(fn) ||
                "Arial".equals(fn)) {
                te.setFontName(FontDefaults.FAMILY);
                te.setPdfFontName(FontDefaults.PDF_NAME);
                te.setPdfEncoding(FontDefaults.PDF_ENCODING);
            }
            // Also override pdfEncoding to Identity-H for all text elements
            // to ensure Cyrillic support in PDF export
            String enc = te.getPdfEncoding();
            if (enc == null || enc.isEmpty() || "CP1252".equals(enc) || "WinAnsiEncoding".equals(enc)) {
                te.setPdfEncoding(FontDefaults.PDF_ENCODING);
            }
        }
    }

    private Object getDesignBand(JasperDesign design, String name) throws Exception {
        String methodName = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        java.lang.reflect.Method m = design.getClass().getMethod(methodName);
        return m.invoke(design);
    }
}
