package io.github.openreportengine.datasource;

import io.github.openreportengine.render.RenderRequest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DataSourceFactory {
    private static final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public static DataSource create(RenderRequest.DataSourceConfig cfg) {
        String poolKey = cfg.url + "|" + cfg.user;
        if (pools.containsKey(poolKey)) {
            return pools.get(poolKey);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(cfg.url);
        if (cfg.user != null) config.setUsername(cfg.user);
        if (cfg.password != null) config.setPassword(cfg.password);
        if (cfg.driver != null) config.setDriverClassName(cfg.driver);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(300000);

        HikariDataSource ds = new HikariDataSource(config);
        pools.put(poolKey, ds);
        return ds;
    }
}
