package com.acme.care.resident.web;

import static org.springframework.web.servlet.function.RequestPredicates.GET;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
public class ResidentRouteConfig {

    @Bean
    public RouterFunction<ServerResponse> residentRoutes(ResidentRouteHandler h) {
        return RouterFunctions.route()
                .path("/api/routes", b -> b
                        .GET("/list", h::list)
                        .POST("/create", h::create))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> pingRoute(ResidentRouteHandler h) {
        return RouterFunctions.route(GET("/api/fn/ping"), h::ping);
    }

    @Bean
    public RouterFunction<ServerResponse> spaFallbackRoute(ResidentRouteHandler h) {
        return RouterFunctions.route(RequestPredicates.accept(MediaType.TEXT_HTML), h::spaIndex);
    }
}
