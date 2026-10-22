package com.acme.care.dailycare.web;

import static org.springframework.web.servlet.function.RouterFunctions.route;

import java.util.UUID;

import com.acme.care.dailycare.rounds.CareRound;
import com.acme.care.dailycare.rounds.CareRoundService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
public class DailyCareRoutes {

    private final CareRoundService rounds;

    public DailyCareRoutes(CareRoundService rounds) {
        this.rounds = rounds;
    }

    @Bean
    public RouterFunction<ServerResponse> dailyCareRoutes() {
        return route()
                .GET("/api/dailycare/rounds", this::listRounds)
                .GET("/api/dailycare/rounds/{roundId}", this::getRound)
                .POST("/api/dailycare/rounds", this::recordRound)
                .build();
    }

    ServerResponse listRounds(ServerRequest request) {
        String unit = request.param("unit").orElse("all");
        return ServerResponse.ok().body(rounds.today(unit));
    }

    ServerResponse getRound(ServerRequest request) {
        UUID roundId = UUID.fromString(request.pathVariable("roundId"));
        return rounds.find(roundId)
                .map(round -> ServerResponse.ok().body(round))
                .orElseGet(() -> ServerResponse.notFound().build());
    }

    ServerResponse recordRound(ServerRequest request) throws Exception {
        CareRound round = rounds.record(request.body(CareRound.class));
        return ServerResponse.status(201).body(round);
    }
}
