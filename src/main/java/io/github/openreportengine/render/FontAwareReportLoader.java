package io.github.openreportengine.render;

import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.design.*;
import net.sf.jasperreports.engine.xml.ReportLoader;
import com.jaspersoft.jasperreports.legacy.xml.LegacyXmlLoader;

import javax.xml.parsers.*;
import javax.xml.xpath.*;
import org.w3c.dom.*;

import java.io.ByteArrayInputStream;
import java.util.*;

public class FontAwareReportLoader implements ReportLoader {

    private static class FontInfo {
        String fontName;
        String pdfFontName;
        String pdfEncoding;
        Boolean isBold;
        Boolean isItalic;
        Integer size;
        String pdfFontName2; // from <font> inside textElement
        String pdfEncoding2;
    }

    @Override
    public Optional<JasperDesign> loadReport(JasperReportsContext ctx, byte[] data) {
        try {
            // Step 1: extract font info from XML
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(data));
            XPath xpath = XPathFactory.newInstance().newXPath();

            // Extract fonts from all textField and staticText
            List<FontInfo> fonts = new ArrayList<>();
            
            // Process textField elements
            NodeList textFields = (NodeList) xpath.evaluate("//textField", doc, XPathConstants.NODESET);
            for (int i = 0; i < textFields.getLength(); i++) {
                Element tf = (Element) textFields.item(i);
                FontInfo fi = extractFontInfo(xpath, tf);
                fonts.add(fi);
            }

            // Process staticText elements
            NodeList staticTexts = (NodeList) xpath.evaluate("//staticText", doc, XPathConstants.NODESET);
            for (int i = 0; i < staticTexts.getLength(); i++) {
                Element st = (Element) staticTexts.item(i);
                FontInfo fi = extractFontInfo(xpath, st);
                fonts.add(fi);
            }

            // Step 2: parse with LegacyXmlLoader
            LegacyXmlLoader loader = new LegacyXmlLoader();
            Optional<JasperDesign> opt = loader.loadReport(ctx, data);
            
            if (opt.isPresent()) {
                JasperDesign design = opt.get();
                
                // Step 3: apply fonts
                applyFonts(design, fonts);
                
                System.err.println("FontAwareReportLoader: applied " + fonts.size() + " fonts to " + design.getName());
                return Optional.of(design);
            }
        } catch (Exception e) {
            System.err.println("FontAwareReportLoader error: " + e.getMessage());
            e.printStackTrace(System.err);
        }
        return Optional.empty();
    }

    @Override
    public Optional<JRTemplate> loadTemplate(JasperReportsContext ctx, byte[] data) {
        return Optional.empty();
    }

    private FontInfo extractFontInfo(XPath xpath, Element element) throws Exception {
        FontInfo fi = new FontInfo();

        // Check textElement for direct attributes
        NodeList textElements = (NodeList) xpath.evaluate("textElement", element, XPathConstants.NODESET);
        if (textElements.getLength() > 0) {
            Element te = (Element) textElements.item(0);
            
            // Check <font> child element
            NodeList fontChildren = te.getElementsByTagName("font");
            if (fontChildren.getLength() > 0) {
                Element fontEl = (Element) fontChildren.item(0);
                fi.fontName = getAttr(fontEl, "fontName");
                fi.pdfFontName = getAttr(fontEl, "pdfFontName");
                fi.pdfEncoding = getAttr(fontEl, "pdfEncoding");
                fi.isBold = getAttrBool(fontEl, "isBold");
                fi.isItalic = getAttrBool(fontEl, "isItalic");
                fi.size = getAttrInt(fontEl, "size");
            }
        }

        // Also check reportElement for generic attributes (some versions put font info there)
        NodeList reportElements = (NodeList) xpath.evaluate("reportElement", element, XPathConstants.NODESET);
        if (reportElements.getLength() > 0) {
            Element re = (Element) reportElements.item(0);
            // Some tools put fontName as style reference
        }

        return fi;
    }

    private void applyFonts(JasperDesign design, List<FontInfo> fonts) {
        int fontIdx = 0;
        
        // Collect all text elements from all bands in order
        List<JRDesignTextElement> allTextElements = new ArrayList<>();
        
        addTextElementsFromBand(allTextElements, design.getTitle());
        addTextElementsFromBand(allTextElements, design.getPageHeader());
        addTextElementsFromBand(allTextElements, design.getColumnHeader());
        if (design.getDetailSection() != null) {
            for (int i = 0; i < design.getDetailSection().getBands().length; i++) {
                addTextElementsFromBand(allTextElements, design.getDetailSection().getBands()[i]);
            }
        }
        addTextElementsFromBand(allTextElements, design.getColumnFooter());
        addTextElementsFromBand(allTextElements, design.getPageFooter());
        addTextElementsFromBand(allTextElements, design.getSummary());
        addTextElementsFromBand(allTextElements, design.getBackground());
        addTextElementsFromBand(allTextElements, design.getLastPageFooter());

        // Apply fonts in order
        for (int i = 0; i < allTextElements.size() && i < fonts.size(); i++) {
            JRDesignTextElement te = allTextElements.get(i);
            FontInfo fi = fonts.get(i);
            
            if (fi.fontName != null && !fi.fontName.isEmpty()) {
                te.setFontName(fi.fontName);
            }
            if (fi.pdfFontName != null && !fi.pdfFontName.isEmpty()) {
                te.setPdfFontName(fi.pdfFontName);
            }
            if (fi.pdfEncoding != null && !fi.pdfEncoding.isEmpty()) {
                te.setPdfEncoding(fi.pdfEncoding);
            }
        }
    }

    private void addTextElementsFromBand(List<JRDesignTextElement> list, JRBand band) {
        if (band == null) return;
        for (JRElement elem : band.getElements()) {
            if (elem instanceof JRDesignTextElement) {
                list.add((JRDesignTextElement) elem);
            }
        }
    }

    private String getAttr(Element el, String name) {
        return el.hasAttribute(name) ? el.getAttribute(name) : null;
    }

    private Boolean getAttrBool(Element el, String name) {
        String v = getAttr(el, name);
        return v != null ? Boolean.parseBoolean(v) : null;
    }

    private Integer getAttrInt(Element el, String name) {
        String v = getAttr(el, name);
        return v != null ? Integer.parseInt(v) : null;
    }
}
