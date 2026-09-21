package com.IGB.BridgeApi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class SqlServerConfig {

    @Value("${sqlserver.url}")
    private String url;

    @Value("${sqlserver.username}")
    private String username;

    @Value("${sqlserver.password}")
    private String password;

    @Value("${sqlserver.driver-class-name}")
    private String driverClassName;


    @Bean(name = "sqlServerJdbcTemplate")
    public JdbcTemplate sqlServerJdbcTemplate() {

        DriverManagerDataSource dataSource =
                new DriverManagerDataSource();

        dataSource.setDriverClassName(
                driverClassName
        );

        dataSource.setUrl(
                url
        );

        dataSource.setUsername(
                username
        );

        dataSource.setPassword(
                password
        );

        return new JdbcTemplate(
                dataSource
        );
    }


    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getDriverClassName() {
        return driverClassName;
    }
}