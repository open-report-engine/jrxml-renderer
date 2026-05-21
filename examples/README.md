# jrxml-renderer examples

## Quick start

Render a PDF with Arial font:

```bash
curl -s -X POST http://localhost:8080/api/render \
  -H "Content-Type: application/json" \
  -d "$(cat hello-world.jrxml | python3 -c 'import json,sys; print(json.dumps({"jrxml": sys.stdin.read(), "format": "pdf"}))')" \
  -o hello-world.pdf
```

Or directly with curl inline:

```bash
curl -s -X POST http://localhost:8080/api/render \
  -H "Content-Type: application/json" \
  -d '{
    "jrxml": "<?xml version=\"1.0\" encoding=\"UTF-8\"?><jasperReport xmlns=\"http://jasperreports.sourceforge.net/jasperreports\" name=\"hello\" pageWidth=\"595\" pageHeight=\"842\" columnWidth=\"555\" leftMargin=\"20\" rightMargin=\"20\" topMargin=\"20\" bottomMargin=\"20\" whenNoDataType=\"AllSectionsNoDetail\"><title><band height=\"30\"><staticText><reportElement x=\"0\" y=\"0\" width=\"555\" height=\"30\"/><textElement><font fontName=\"Arial\" pdfFontName=\"Arial\" pdfEncoding=\"Identity-H\"/></textElement><text><![CDATA[Привет, мир!]]></text></staticText></band></title></jasperReport>",
    "format": "pdf"
  }' \
  -o hello-world.pdf
```

## Examples

### 1. Hello World (hello-world.jrxml)

Minimal example with Arial font and cyrillic text.

```bash
# PDF
curl -s -X POST http://localhost:8080/api/render \
  -H "Content-Type: application/json" \
  -d "$(python3 -c "import json; f=open('hello-world.jrxml'); print(json.dumps({'jrxml': f.read(), 'format': 'pdf'}))")" \
  -o hello-world.pdf

# XLSX
curl -s -X POST http://localhost:8080/api/render \
  -H "Content-Type: application/json" \
  -d "$(python3 -c "import json; f=open('hello-world.jrxml'); print(json.dumps({'jrxml': f.read(), 'format': 'xlsx'}))")" \
  -o hello-world.xls

# DOCX
curl -s -X POST http://localhost:8080/api/render \
  -H "Content-Type: application/json" \
  -d "$(python3 -c "import json; f=open('hello-world.jrxml'); print(json.dumps({'jrxml': f.read(), 'format': 'docx'}))")" \
  -o hello-world.docx

# CSV
curl -s -X POST http://localhost:8080/api/render \
  -H "Content-Type: application/json" \
  -d "$(python3 -c "import json; f=open('hello-world.jrxml'); print(json.dumps({'jrxml': f.read(), 'format': 'csv'}))")" \
  -o hello-world.csv
```

### 2. Font test table (font-test-table.jrxml)

Styled table with borders, alternating row colors, and green status cells.

```bash
# PDF
curl -s -X POST http://localhost:8080/api/render \
  -H "Content-Type: application/json" \
  -d "$(python3 -c "import json; f=open('font-test-table.jrxml'); print(json.dumps({'jrxml': f.read(), 'format': 'pdf'}))")" \
  -o font-test-table.pdf

# XLSX
curl -s -X POST http://localhost:8080/api/render \
  -H "Content-Type: application/json" \
  -d "$(python3 -c "import json; f=open('font-test-table.jrxml'); print(json.dumps({'jrxml': f.read(), 'format': 'xlsx'}))")" \
  -o font-test-table.xls
```

### 3. Form 1 (form1.jrxml)

Example of a real report form with data fields, variables and SUM calculations.

```bash
curl -s -X POST http://localhost:8080/api/render \
  -H "Content-Type: application/json" \
  -d "$(python3 -c "import json; f=open('form1.jrxml'); print(json.dumps({'jrxml': f.read(), 'format': 'pdf'}))")" \
  -o form1.pdf
```

## Font requirements

All examples use **Arial** font. To use other fonts:

1. Mount your `.ttf` files to `/fonts/` directory in the container:
   ```yaml
   volumes:
     - ./my-fonts:/fonts:ro
   ```

2. Specify the font name in JRXML:
   ```xml
   <font fontName="My Font" pdfFontName="My Font" pdfEncoding="Identity-H"/>
   ```

3. The renderer automatically registers all fonts from `/fonts/` and `/app/` directories on startup.

## Supported formats

| Format | Content-Type |
|--------|-------------|
| `pdf`  | application/pdf |
| `xlsx`  | application/vnd.openxmlformats-officedocument.spreadsheetml.sheet |
| `docx` | application/vnd.openxmlformats-officedocument.wordprocessingml.document |
| `csv`  | text/csv |
