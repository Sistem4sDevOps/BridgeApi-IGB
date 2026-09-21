package com.IGB.BridgeApi.config;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig
        extends WebSecurityConfigurerAdapter {


    @Autowired
    private JwtRequestFilter jwtRequestFilter;


    @Override
    protected void configure(
            HttpSecurity http
    ) throws Exception {


        http

                /* =================================================
                   CORS
                   ================================================= */

                .cors()

                .and()


                /* =================================================
                   CSRF
                   API REST STATELESS
                   ================================================= */

                .csrf()
                .disable()


                /* =================================================
                   SESIONES
                   ================================================= */

                .sessionManagement()
                .sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS
                )

                .and()


                /* =================================================
                   AUTORIZACIONES
                   ================================================= */

                .authorizeRequests()


                /* OPTIONS PARA CORS */

                .antMatchers(
                        org.springframework.http.HttpMethod.OPTIONS,
                        "/**"
                )
                .permitAll()


                /* LOGIN */

                .antMatchers(
                        "/authenticate/**"
                )
                .permitAll()


                /* WALI */

                .antMatchers(
                        "/wali/**"
                )
                .permitAll()


                /* REDPLAS */

                .antMatchers(
                        "/redplas/**"
                )
                .permitAll()


                /* TWILIO */

                .antMatchers(
                        "/twilio/**"
                )
                .permitAll()


                /* FEMPROBIEN */

                .antMatchers(
                        "/femprobien/**"
                )
                .permitAll()


                /* ACTUATOR */

                .antMatchers(
                        "/actuator/health",
                        "/actuator/info"
                )
                .permitAll()


                /* RESTO REQUIERE JWT */

                .anyRequest()
                .authenticated();


        /* =========================================================
           FILTRO JWT
           ========================================================= */

        http.addFilterBefore(

                jwtRequestFilter,

                UsernamePasswordAuthenticationFilter.class
        );
    }
}