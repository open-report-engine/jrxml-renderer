![Build](https://github.com/open-report-engine/jrxml-renderer/workflows/CI/badge.svg)
![License](https://img.shields.io/badge/license-Apache%202.0-blue)
![Size](https://img.shields.io/docker/image-size/open-report-engine/jrxml-renderer/latest)

# JRXML Renderer

**HTTP-микросервис для рендеринга JRXML-шаблонов JasperReports в PDF и XLSX.**

Лёгкая, stateless, open-source альтернатива JasperReports Server — для команд, которым нужно рендерить отчёты, а не запускать полноценную BI-платформу.

---

## Сравнение с JasperReports Server

| Характеристика | JRXML Renderer | JasperReports Server |
|---|---|---|
| **Архитектура** | Один JAR, встроенный HTTP-сервер | Tomcat + Spring + Teiid + Mondrian |
| **Формат JRXML** | Jackson (Studio 7.x нативный) | Только Digester (Legacy 6.x) |
| **Зависимости** | Минимальные: JasperReports Lib + Undertow | 300+ JARов, Teiid, JBoss, проприетарные |
| **Размер образа** | ~180 MB | ~800 MB |
| **Время старта** | < 1 секунды | 30-60 секунд |
| **Лицензия** | Apache 2.0 | AGPL / Коммерческая |
| **Границы в таблицах** | Полная поддержка (JasperReports 7.0.6) | Ограничена (JasperReports 6.20.3) |

## Преимущества

- **✅ Совместимость со Studio 7.x** — работает с JRXML из Jaspersoft Studio 7.0.6 "как есть"
- **✅ Границы в таблицах** — полная поддержка через `<style>` и `<box>` 
- **✅ Кириллица и Unicode** — шрифты DejaVu с Identity-H
- **✅ ClickHouse и PostgreSQL** — встроенная поддержка JDBC
- **✅ Stateless** — каждый запрос независим, нет сессий и хранилища
- **✅ Аутентификация** — опциональный Bearer token

---

## API

### POST /api/render

```http
POST /api/render
Content-Type: application/json
Authorization: Bearer <token>    # опционально
```

#### Тело запроса

```json
{
  "jrxml": "<?xml version=\"1.0\" encoding=\"UTF-8\"?>...",
  "format": "pdf",
  "data_source": {
    "type": "sql",
    "url": "jdbc:clickhouse://host:8123/db?compress=0",
    "user": "default",
    "password": "",
    "query": "SELECT ..."
  },
  "parameters": {
    "period": "2026-Q1"
  }
}
```

#### Ответ

```
200 OK
Content-Type: application/pdf
Content-Disposition: attachment; filename="report.pdf"

<PDF binary data>
```

#### Типы data_source

| Тип | Описание | Обязательные поля |
|---|---|---|
| `sql` | Выполнить SQL-запрос к JDBC-источнику | `url`, `user`, `password`, `query` |
| `none` | Использовать встроенный запрос JRXML или пустые данные | — |

#### Форматы

| Формат | Content-Type | Экспортёр |
|---|---|---|
| `pdf` | `application/pdf` | JasperReports PDF (iText/OpenPDF) |
| `xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | Apache POI |

---

## Быстрый старт

### Docker

```bash
docker run -p 8080:8080 ghcr.io/open-report-engine/jrxml-renderer:latest
```

### Тест

```bash
curl -X POST http://localhost:8080/api/render \
  -H "Content-Type: application/json" \
  -d '{
    "jrxml": "<?xml version=\"1.0\" encoding=\"UTF-8\"?><jasperReport xmlns=\"http://jasperreports.sourceforge.net/jasperreports\" name=\"Hello\" pageWidth=\"595\" pageHeight=\"842\"><detail><band height=\"20\"><textField><reportElement x=\"0\" y=\"0\" width=\"300\" height=\"20\"/><textElement><font fontName=\"DejaVu Sans\" size=\"12\"/></textElement><textFieldExpression><![CDATA[\"Привет, мир!\"]]></textFieldExpression></textField></band></detail></jasperReport>",
    "format": "pdf"
  }' \
  -o hello.pdf
```

---

## Конфигурация

| Переменная окружения | По умолчанию | Описание |
|---|---|---|
| `PORT` | `8080` | Порт HTTP-сервера |
| `AUTH_TOKEN` | — | Bearer token для аутентификации (пусто = отключено) |

---

## Сборка из исходников

```bash
git clone https://github.com/open-report-engine/jrxml-renderer.git
cd jrxml-renderer
mvn clean package -DskipTests
docker build -t jrxml-renderer .
docker run -p 8080:8080 jrxml-renderer
```

### Требования

- JDK 21+
- Maven 3.9+
- Docker (опционально)

---

## Зависимости

| Библиотека | Версия | Лицензия | Назначение |
|---|---|---|---|
| JasperReports | 7.0.6 | [LGPL 3.0](https://www.gnu.org/licenses/lgpl-3.0.html) | Основной движок отчётов |
| JasperReports PDF | 7.0.6 | LGPL 3.0 | Экспорт в PDF |
| JasperReports Excel POI | 7.0.6 | LGPL 3.0 | Экспорт в XLS/XLSX |
| JasperReports Fonts | 7.0.6 | LGPL 3.0 | Шрифты DejaVu |
| LJP (Legacy JRXML Parser) | 7.0.6 | [EPL 2.0](https://www.eclipse.org/legal/epl-2.0/) | Digester-парсер для обратной совместимости |
| Undertow | 2.3 | Apache 2.0 | Встроенный HTTP-сервер |
| ClickHouse JDBC | 0.5.0 | Apache 2.0 | Подключение к ClickHouse |
| PostgreSQL JDBC | 42.7 | BSD 2-Clause | Подключение к PostgreSQL |
| HikariCP | 6.2 | Apache 2.0 | Пул соединений |

---

## Архитектура

```
┌──────────────┐      POST /api/render       ┌──────────────────┐
│   curl / app  │  ─────────────────────────→ │  jrxml-renderer  │
│              │  {                          │                  │
│              │    "jrxml": "...",          │  JacksonParser   │
│              │    "format": "pdf",         │    ↓             │
│              │    "data_source": { ... }   │  CompileReport   │
│              │    "parameters": { ... }    │    ↓             │
│              │  }                          │  FillReport      │
│              │                             │    ↓             │
│              │  ←───────────────────────── │  Export → PDF    │
│              │  200 OK, application/pdf    │       или XLSX   │
└──────────────┘                             └──────────────────┘
```

### Выбор парсера

При обработке запроса `jrxml-renderer` последовательно пробует два парсера JRXML:

1. **JacksonReportLoader** — для JasperReports 7.x Jackson-формата (Studio 7.x)
2. **LegacyXmlLoader** (LJP) — для Digester-формата (Studio 6.x и ниже)

Используется первый подошедший парсер. Это обеспечивает совместимость как с новыми, так и со старыми шаблонами.

---

## Структура проекта

```
jrxml-renderer/
├── src/main/java/io/github/openreportengine/
│   ├── App.java                    # Точка входа
│   ├── api/RenderHandler.java      # HTTP-обработчик POST /api/render
│   ├── render/
│   │   ├── RenderRequest.java      # DTO запроса + парсер
│   │   └── RenderService.java      # Основная логика рендеринга
│   └── datasource/
│       └── DataSourceFactory.java  # Пул JDBC-соединений
├── Dockerfile                      # Alpine + JRE 21
├── pom.xml                         # Maven-проект
└── lib/                            # LJP JAR (Legacy JRXML Parser)
```

---

## Лицензия

**JRXML Renderer** — Apache 2.0

**LJP (Legacy JRXML Parser)** — EPL 2.0 (форк JasperReports legacy-модуля с удалённой проверкой лицензии)

JasperReports Library и сопутствующие модули распространяются под LGPL 3.0.
