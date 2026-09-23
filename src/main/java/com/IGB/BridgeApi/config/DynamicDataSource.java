package com.IGB.BridgeApi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

@Component
public class DynamicDataSource {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.driverClassName}")
    private String driverClassName;

    public DriverManagerDataSource getDataSource(String schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        String dynamicUrl = url + "&currentSchema=" + schema;
        dataSource.setDriverClassName(driverClassName);
        dataSource.setUrl(dynamicUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
