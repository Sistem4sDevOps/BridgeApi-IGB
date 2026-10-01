package com.IGB.BridgeApi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private final JwtRequestFilter jwtRequestFilter;

    public SecurityConfig(JwtRequestFilter jwtRequestFilter) {
        this.jwtRequestFilter = jwtRequestFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {

        http
                .cors()
                .and()
                .csrf()
                .disable()
                .authorizeRequests()

                .antMatchers(HttpMethod.OPTIONS, "/**")
                .permitAll()

                .antMatchers("/authenticate/**")
                .permitAll()

                .antMatchers("/wali/**")
                .permitAll()

                .antMatchers("/redplas/**")
                .permitAll()

                .antMatchers("/twilio/**")
                .permitAll()

                /*
                 * FEMPROBIEN no requiere JWT dentro de BridgeApi.
                 *
                 * La Fase 3 valida los permisos de administración
                 * en FemprobienRolesController contra SQL Server.
                 */
                .antMatchers("/femprobien/**")
                .permitAll()

                .anyRequest()
                .authenticated()

                .and()
                .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS);

        /*
         * Se conserva el filtro JWT para las demás rutas del proyecto.
         * JwtRequestFilter omite /femprobien/**.
         */
        http.addFilterBefore(
                jwtRequestFilter,
                UsernamePasswordAuthenticationFilter.class
        );
    }
}
