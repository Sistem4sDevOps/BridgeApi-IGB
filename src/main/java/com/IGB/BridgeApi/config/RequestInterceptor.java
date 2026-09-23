package com.IGB.BridgeApi.config;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class RequestInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String warehouseCode = request.getHeader("X-Warehouse-Code");
        String authorization = request.getHeader("Authorization");
        String employee = request.getHeader("X-Employee");
        String pruebas = request.getHeader("X-Pruebas");

        System.out.println("X-Warehouse-Code: " + warehouseCode);
        System.out.println("Authorization: " + authorization);
        System.out.println("X-Employee: " + employee);
        System.out.println("X-Pruebas: " + pruebas);

        return true;
    }
}
