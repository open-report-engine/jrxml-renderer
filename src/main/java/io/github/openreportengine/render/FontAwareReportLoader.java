package io.github.openreportengine.render;

import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.design.*;
import net.sf.jasperreports.engine.xml.JRXmlLoader;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.io.ByteArrayInputStream;
import java.util.*;

public class FontAwareReportLoader {

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

        // Step 2: Fix band origins on section-based bands (detail, groupHeader/Footer).
        // JacksonReportLoader with @JsonMerge does not set JROrigin on bands inside
        // JRDesignSection. Without origin, fillReport cannot map data to $F{} fields.
        resetSectionOrigins(design);

        // Step 3: Apply font fallback — replace default/Helvetica with DejaVu Sans Mono
        applyArialFallback(design);

        System.err.println("FontAwareReportLoader: fonts applied to " + design.getName());
        return design;
    }

    private void resetSectionOrigins(JasperDesign design) {
        try {
            java.lang.reflect.Method getDetail = design.getClass().getMethod("getDetailSection");
            setSectionOrigin((JRSection) getDetail.invoke(design), net.sf.jasperreports.engine.type.BandTypeEnum.DETAIL);
        } catch (Exception e) {
            System.err.println("detail origin: " + e.getMessage());
        }
        // Group sections are accessed via getGroupsList()
        try {
            for (JRGroup group : design.getGroupsList()) {
                if (group instanceof JRDesignGroup) {
                    JRDesignGroup dg = (JRDesignGroup) group;
                    setSectionOrigin(dg.getGroupHeaderSection(), net.sf.jasperreports.engine.type.BandTypeEnum.GROUP_HEADER);
                    setSectionOrigin(dg.getGroupFooterSection(), net.sf.jasperreports.engine.type.BandTypeEnum.GROUP_FOOTER);
                }
            }
        } catch (Exception e) {
            System.err.println("group origin: " + e.getMessage());
        }
    }

    private void setSectionOrigin(JRSection section, net.sf.jasperreports.engine.type.BandTypeEnum type) {
        if (section == null) return;
        JROrigin origin = new JROrigin(type);
        for (JRBand band : section.getBands()) {
            if (band instanceof JRDesignBand) {
                try {
                    java.lang.reflect.Method setOrigin = JRDesignBand.class.getDeclaredMethod("setOrigin", JROrigin.class);
                    setOrigin.setAccessible(true);
                    setOrigin.invoke(band, origin);
                } catch (Exception e) {
                    System.err.println("setOrigin error: " + e.getMessage());
                }
            }
        }
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
        // Process group header/footer section bands
        try {
            for (JRGroup group : design.getGroupsList()) {
                if (group instanceof JRDesignGroup) {
                    JRDesignGroup dg = (JRDesignGroup) group;
                    if (dg.getGroupHeaderSection() != null) {
                        for (JRBand b : dg.getGroupHeaderSection().getBands()) {
                            applyArialToBand((JRDesignBand) b);
                        }
                    }
                    if (dg.getGroupFooterSection() != null) {
                        for (JRBand b : dg.getGroupFooterSection().getBands()) {
                            applyArialToBand((JRDesignBand) b);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("group bands font fallback: " + e.getMessage());
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
