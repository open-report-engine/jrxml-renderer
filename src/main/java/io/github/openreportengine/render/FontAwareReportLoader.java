package io.github.openreportengine.render;

import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.design.*;
import net.sf.jasperreports.engine.type.OrientationEnum;

import javax.xml.parsers.*;
import javax.xml.xpath.*;
import org.w3c.dom.*;

import java.io.ByteArrayInputStream;
import java.util.*;

public class FontAwareReportLoader {

    private final List<JRDesignBand> detailBands = new ArrayList<>();

    private static class FontInfo {
        String fontName;
        String pdfFontName;
        String pdfEncoding;
    }

    public JasperDesign loadDesign(JasperReportsContext ctx, byte[] data) throws Exception {
        // Strip namespace from XML for simpler parsing
        String xmlStr = new String(data, "UTF-8");
        xmlStr = xmlStr.replaceAll("xmlns=\"[^\"]*\"", "");
        // Also remove namespace prefixes (e.g., <jr:xxx> -> <xxx>)
        xmlStr = xmlStr.replaceAll("<(\\w+):(\\w+)", "<$2");
        xmlStr = xmlStr.replaceAll("</(\\w+):(\\w+)", "</$2");
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ByteArrayInputStream(xmlStr.getBytes("UTF-8")));
        XPath xpath = XPathFactory.newInstance().newXPath();

        Element root = doc.getDocumentElement();

        String name = root.getAttribute("name");
        int pageW = parseIntOrDefault(root.getAttribute("pageWidth"), 595);
        int pageH = parseIntOrDefault(root.getAttribute("pageHeight"), 842);
        String orient = root.getAttribute("orientation");

        JasperDesign design = new JasperDesign();
        design.setName(name != null ? name : "Report");
        design.setPageWidth(pageW);
        design.setPageHeight(pageH);

        if ("Landscape".equalsIgnoreCase(orient) || "landscape".equalsIgnoreCase(orient)) {
            design.setOrientation(OrientationEnum.LANDSCAPE);
        } else {
            design.setOrientation(OrientationEnum.PORTRAIT);
        }

        design.setColumnWidth(parseIntOrDefault(root.getAttribute("columnWidth"), pageW - 40));
        design.setColumnSpacing(parseIntOrDefault(root.getAttribute("columnSpacing"), 0));
        design.setColumnCount(1);
        design.setLeftMargin(parseIntOrDefault(root.getAttribute("leftMargin"), 20));
        design.setRightMargin(parseIntOrDefault(root.getAttribute("rightMargin"), 20));
        design.setTopMargin(parseIntOrDefault(root.getAttribute("topMargin"), 20));
        design.setBottomMargin(parseIntOrDefault(root.getAttribute("bottomMargin"), 20));

        String whenNoData = root.getAttribute("whenNoDataType");
        if ("AllSectionsNoDetail".equals(whenNoData)) {
            design.setWhenNoDataType(net.sf.jasperreports.engine.type.WhenNoDataTypeEnum.ALL_SECTIONS_NO_DETAIL);
        } else if ("NoDataSection".equals(whenNoData)) {
            design.setWhenNoDataType(net.sf.jasperreports.engine.type.WhenNoDataTypeEnum.NO_DATA_SECTION);
        } else if ("BlankPage".equals(whenNoData)) {
            design.setWhenNoDataType(net.sf.jasperreports.engine.type.WhenNoDataTypeEnum.BLANK_PAGE);
        } else {
            design.setWhenNoDataType(net.sf.jasperreports.engine.type.WhenNoDataTypeEnum.ALL_SECTIONS_NO_DETAIL);
        }

        // Process parameters
        NodeList paramNodes = (NodeList) xpath.evaluate("/jasperReport/parameter", doc, XPathConstants.NODESET);
        for (int i = 0; i < paramNodes.getLength(); i++) {
            Element pe = (Element) paramNodes.item(i);
            JRDesignParameter param = new JRDesignParameter();
            String pname = pe.getAttribute("name");
            param.setName(pname);
            String pclass = pe.getAttribute("class");
            if (pclass != null && !pclass.isEmpty()) {
                try {
                    param.setValueClass(Class.forName(pclass));
                } catch (ClassNotFoundException e) {
                    param.setValueClassName(pclass);
                }
            }
            design.addParameter(param);
        }

        // Process fields
        NodeList fieldNodes = (NodeList) xpath.evaluate("/jasperReport/field", doc, XPathConstants.NODESET);
        for (int i = 0; i < fieldNodes.getLength(); i++) {
            Element fe = (Element) fieldNodes.item(i);
            JRDesignField field = new JRDesignField();
            String fname = fe.getAttribute("name");
            field.setName(fname);
            String fclass = fe.getAttribute("class");
            if (fclass != null && !fclass.isEmpty()) {
                try {
                    field.setValueClass(Class.forName(fclass));
                } catch (ClassNotFoundException e) {
                    field.setValueClassName(fclass);
                }
            }
            design.addField(field);
        }

        // Process variables
        NodeList varNodes = (NodeList) xpath.evaluate("/jasperReport/variable", doc, XPathConstants.NODESET);
        for (int i = 0; i < varNodes.getLength(); i++) {
            Element ve = (Element) varNodes.item(i);
            JRDesignVariable var = new JRDesignVariable();
            String vname = ve.getAttribute("name");
            var.setName(vname);
            String vclass = ve.getAttribute("class");
            if (vclass != null && !vclass.isEmpty()) {
                try {
                    var.setValueClass(Class.forName(vclass));
                } catch (ClassNotFoundException e) {
                    var.setValueClassName(vclass);
                }
            }
            String calc = ve.getAttribute("calculation");
            if ("Sum".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.SUM);
            else if ("Count".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.COUNT);
            else if ("Average".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.AVERAGE);
            else if ("First".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.FIRST);
            else if ("DistinctCount".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.DISTINCT_COUNT);
            else if ("Lowest".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.LOWEST);
            else if ("Highest".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.HIGHEST);
            else if ("StandardDeviation".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.STANDARD_DEVIATION);
            else if ("Variance".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.VARIANCE);
            else var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.NOTHING);

            Element varExpr = getChildElement(ve, "variableExpression");
            if (varExpr != null) {
                String exprText = varExpr.getTextContent();
                if (exprText != null) {
                    exprText = exprText.trim();
                    if (exprText.startsWith("<![CDATA[")) {
                        exprText = exprText.substring(9, exprText.length() - 3);
                    }
                }
                if (exprText != null && !exprText.isEmpty()) {
                    var.setExpression(new JRDesignExpression(exprText));
                }
            }
            Element initExpr = getChildElement(ve, "initialValueExpression");
            if (initExpr != null) {
                String exprText = initExpr.getTextContent();
                if (exprText != null) {
                    exprText = exprText.trim();
                    if (exprText.startsWith("<![CDATA[")) {
                        exprText = exprText.substring(9, exprText.length() - 3);
                    }
                }
                if (exprText != null && !exprText.isEmpty()) {
                    var.setInitialValueExpression(new JRDesignExpression(exprText));
                }
            }
            design.addVariable(var);
        }

        // Process queryString
        NodeList queryNodes = (NodeList) xpath.evaluate("/jasperReport/queryString", doc, XPathConstants.NODESET);
        if (queryNodes.getLength() > 0) {
            Element qe = (Element) queryNodes.item(0);
            String queryText = qe.getTextContent();
            if (queryText != null) {
                queryText = queryText.trim();
                if (queryText.startsWith("<![CDATA[")) {
                    queryText = queryText.substring(9, queryText.length() - 3);
                }
            }
            if (queryText != null && !queryText.isEmpty()) {
                JRDesignQuery query = new JRDesignQuery();
                query.setText(queryText);
                String lang = qe.getAttribute("language");
                if (lang != null && !lang.isEmpty()) {
                    query.setLanguage(lang);
                }
                design.setQuery(query);
            }
        }

        // Process bands
        String[] bandNames = {"title", "pageHeader", "columnHeader", "detail", "columnFooter", "pageFooter", "summary", "background", "lastPageFooter"};
        for (String bandName : bandNames) {
            NodeList bandNodes = (NodeList) xpath.evaluate("/jasperReport/" + bandName, doc, XPathConstants.NODESET);
            if (bandNodes.getLength() == 0) {
                System.err.println("Band " + bandName + ": not found");
                continue;
            }

            Element sectionEl = (Element) bandNodes.item(0);
            // The XPath returns e.g. <title> which contains <band>
            // Find the <band> child inside
            Element bandEl = getChildElement(sectionEl, "band");
            if (bandEl == null) continue;

            int bandHeight = parseIntOrDefault(bandEl.getAttribute("height"), 30);
            JRDesignBand band = new JRDesignBand();
            band.setHeight(bandHeight);

            // Process staticText elements
            processStaticTexts(xpath, bandEl, band);

            // Process textField elements
            processTextFields(xpath, bandEl, band);

            // Set band on design (JasperDesign has these methods directly)
            if ("detail".equals(bandName)) {
                detailBands.add(band);
            } else {
                setDesignBand(design, bandName, band);
            }
        }

        // Set detail section via getDetailSection().addBand()
        if (!detailBands.isEmpty()) {
            try {
                java.lang.reflect.Method getDetail = design.getClass().getMethod("getDetailSection");
                Object section = getDetail.invoke(design);
                if (section != null) {
                    java.lang.reflect.Method addBand = section.getClass().getMethod("addBand", JRBand.class);
                    for (JRDesignBand b : detailBands) {
                        addBand.invoke(section, b);
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to set detail section: " + e.getMessage());
            }
        }

        // Apply Arial as fallback for any element without explicit font
        applyArialFallback(design);

        // Debug: count elements
        try {
            int elemCount = 0;
            java.lang.reflect.Method getTitle = design.getClass().getMethod("getTitle");
            Object titleBand = getTitle.invoke(design);
            if (titleBand != null) {
                java.lang.reflect.Method getElements = titleBand.getClass().getMethod("getElements");
                JRElement[] elems = (JRElement[]) getElements.invoke(titleBand);
                elemCount += elems.length;
            }
            System.err.println("FontAwareReportLoader: parsed " + design.getName() + " with " + elemCount + " title elements");
        } catch (Exception e) {
            System.err.println("debug count error: " + e.getMessage());
        }
        return design;
    }

    private void applyFontFromElement(Element elementEl, JRDesignTextElement te) {
        Element teEl = getChildElement(elementEl, "textElement");
        if (teEl == null) return;

        Element fontEl = getChildElement(teEl, "font");
        if (fontEl == null) return;

        String fn = fontEl.getAttribute("fontName");
        if (fn != null && !fn.isEmpty()) te.setFontName(fn);

        String pdf = fontEl.getAttribute("pdfFontName");
        if (pdf != null && !pdf.isEmpty()) te.setPdfFontName(pdf);

        String enc = fontEl.getAttribute("pdfEncoding");
        if (enc != null && !enc.isEmpty()) te.setPdfEncoding(enc);
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
    }

    private void applyArialToBand(JRDesignBand band) {
        for (JRElement elem : band.getElements()) {
            if (!(elem instanceof JRDesignTextElement)) continue;
            JRDesignTextElement te = (JRDesignTextElement) elem;
            if (te.getFontName() == null || te.getFontName().isEmpty() ||
                "Helvetica".equals(te.getFontName()) || "SansSerif".equals(te.getFontName())) {
                te.setFontName("Arial");
                te.setPdfFontName("Arial");
                te.setPdfEncoding("Identity-H");
            }
        }
    }

    private Element getChildElement(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String nodeName = child.getNodeName();
                // Strip namespace prefix if present
                int colon = nodeName.indexOf(':');
                String localName = colon >= 0 ? nodeName.substring(colon + 1) : nodeName;
                if (name.equals(localName)) {
                    return (Element) child;
                }
            }
        }
        return null;
    }

    private void processStaticTexts(XPath xpath, Element bandEl, JRDesignBand band) throws Exception {
        NodeList children = bandEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String nodeName = child.getNodeName();
            int colon = nodeName.indexOf(':');
            String localName = colon >= 0 ? nodeName.substring(colon + 1) : nodeName;
            if (!"staticText".equals(localName)) continue;

            Element stEl = (Element) child;
            JRDesignStaticText st = new JRDesignStaticText();

            Element re = getChildElement(stEl, "reportElement");
            if (re != null) {
                st.setX(parseIntOrDefault(re.getAttribute("x"), 0));
                st.setY(parseIntOrDefault(re.getAttribute("y"), 0));
                st.setWidth(parseIntOrDefault(re.getAttribute("width"), 100));
                st.setHeight(parseIntOrDefault(re.getAttribute("height"), 20));
            }

            applyFontFromElement(stEl, st);

            Element textEl = getChildElement(stEl, "text");
            if (textEl != null) {
                String txt = textEl.getTextContent();
                if (txt != null) {
                    txt = txt.trim();
                    if (txt.startsWith("<![CDATA[")) {
                        txt = txt.substring(9, txt.length() - 3);
                    }
                    st.setText(txt);
                }
            }

            band.addElement(st);
        }
    }

    private void processTextFields(XPath xpath, Element bandEl, JRDesignBand band) throws Exception {
        NodeList children = bandEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String nodeName = child.getNodeName();
            int colon = nodeName.indexOf(':');
            String localName = colon >= 0 ? nodeName.substring(colon + 1) : nodeName;
            if (!"textField".equals(localName)) continue;

            Element tfEl = (Element) child;
            JRDesignTextField tf = new JRDesignTextField();

            Element re = getChildElement(tfEl, "reportElement");
            if (re != null) {
                tf.setX(parseIntOrDefault(re.getAttribute("x"), 0));
                tf.setY(parseIntOrDefault(re.getAttribute("y"), 0));
                tf.setWidth(parseIntOrDefault(re.getAttribute("width"), 100));
                tf.setHeight(parseIntOrDefault(re.getAttribute("height"), 20));
            }

            applyFontFromElement(tfEl, tf);

            Element exprEl = getChildElement(tfEl, "textFieldExpression");
            if (exprEl != null) {
                String exprText = exprEl.getTextContent();
                if (exprText != null) {
                    exprText = exprText.trim();
                    if (exprText.startsWith("<![CDATA[")) {
                        exprText = exprText.substring(9, exprText.length() - 3);
                    }
                }
                if (exprText != null && !exprText.isEmpty()) {
                    JRDesignExpression expr = new JRDesignExpression(exprText);
                    tf.setExpression(expr);
                }
            }

            band.addElement(tf);
        }
    }

    private void setDesignBand(JasperDesign design, String name, JRDesignBand band) throws Exception {
        String methodName = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        java.lang.reflect.Method m = design.getClass().getMethod(methodName, JRBand.class);
        m.invoke(design, band);
    }

    private Object getDesignBand(JasperDesign design, String name) throws Exception {
        String methodName = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        java.lang.reflect.Method m = design.getClass().getMethod(methodName);
        return m.invoke(design);
    }

    private int parseIntOrDefault(String value, int def) {
        if (value == null || value.isEmpty()) return def;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
