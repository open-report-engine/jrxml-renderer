package io.github.openreportengine;

import io.github.openreportengine.api.RenderHandler;
import io.undertow.Undertow;
import io.undertow.server.RoutingHandler;

import java.util.logging.Level;
import java.util.logging.Logger;

public class App {
    public static void main(String[] args) throws Exception {
        Logger.getLogger("").setLevel(Level.OFF);

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String authToken = System.getenv("AUTH_TOKEN");

        RoutingHandler router = new RoutingHandler();
        router.post("/api/render", new RenderHandler(authToken));

        Undertow server = Undertow.builder()
            .addHttpListener(port, "0.0.0.0")
            .setHandler(router)
            .build();

        server.start();
        System.out.println("jrxml-renderer started on port " + port);
    }
}
