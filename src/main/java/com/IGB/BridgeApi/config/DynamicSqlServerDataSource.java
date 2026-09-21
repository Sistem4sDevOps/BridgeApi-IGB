package com.IGB.BridgeApi.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class DynamicSqlServerDataSource {

    @Autowired
    private SqlServerConfig sqlServerConfig;

    public DriverManagerDataSource getDataSource(String schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        // Construye la URL de la base de datos con el esquema dinámico
        String dynamicUrl = String.format("%s;databaseName=%s", sqlServerConfig.getUrl(), schema);
        dataSource.setDriverClassName(sqlServerConfig.getDriverClassName());
        dataSource.setUrl(dynamicUrl);
        dataSource.setUsername(sqlServerConfig.getUsername());
        dataSource.setPassword(sqlServerConfig.getPassword());
        return dataSource;
    }
}
