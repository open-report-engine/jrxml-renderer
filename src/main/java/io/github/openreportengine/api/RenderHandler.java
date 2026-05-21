package io.github.openreportengine.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.openreportengine.render.RenderRequest;
import io.github.openreportengine.render.RenderService;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;

public class RenderHandler implements HttpHandler {
    private final String authToken;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RenderService renderService = new RenderService();

    public RenderHandler(String authToken) {
        this.authToken = authToken;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.getRequestMethod().toString().equals("OPTIONS")) {
            exchange.setStatusCode(204);
            exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");
            exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Methods"), "POST, OPTIONS");
            exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Headers"), "Content-Type, Authorization");
            exchange.endExchange();
            return;
        }

        if (isAuthEnabled() && !checkAuth(exchange)) {
            exchange.setStatusCode(401);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"error\":\"Unauthorized\"}");
            return;
        }

        exchange.getRequestReceiver().receiveFullBytes((ex, data) -> {
            try {
                JsonNode req = mapper.readTree(data);
                RenderRequest request = RenderRequest.fromJson(req);

                ByteArrayOutputStream baos = renderService.render(request);

                String contentType = request.format.equals("xlsx")
                    ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    : "application/pdf";

                exchange.setStatusCode(200);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
                exchange.getResponseHeaders().put(Headers.CONTENT_DISPOSITION,
                    "attachment; filename=\"report." + request.format + "\"");
                exchange.getResponseSender().send(ByteBuffer.wrap(baos.toByteArray()));
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                String trace = sw.toString().replace("\"", "'").replace("\n", "\\\\n");
                System.err.println("RENDER ERROR: " + e.getMessage());
                e.printStackTrace(System.err);
                exchange.setStatusCode(500);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send("{\"error\":\"" + trace.substring(0, Math.min(trace.length(), 2000)) + "\"}");
            }
        });
    }

    private boolean isAuthEnabled() {
        return authToken != null && !authToken.isEmpty();
    }

    private boolean checkAuth(HttpServerExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst(Headers.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) return false;
        return auth.substring(7).equals(authToken);
    }
}
