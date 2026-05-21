package io.github.openreportengine.config;

public class AppConfig {
    public final int port;
    public final String authToken;

    public AppConfig() {
        this.port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String token = System.getenv("AUTH_TOKEN");
        if (token != null && token.isEmpty()) {
            token = null;
        }
        this.authToken = token;
    }

    public boolean isAuthEnabled() {
        return authToken != null && !authToken.isEmpty();
    }
}
