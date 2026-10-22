package com.acme.care.resident.web;

import com.acme.care.resident.service.ResidentDirectoryService;
import com.acme.care.resident.service.ResidentDirectoryService.DirectoryEntry;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Component
public class ResidentRouteHandler {

    private final ResidentDirectoryService directory;

    public ResidentRouteHandler(ResidentDirectoryService directory) {
        this.directory = directory;
    }

    ServerResponse list(ServerRequest request) {
        return ServerResponse.ok().body(directory.list(request.param("unit").orElse(null)));
    }

    ServerResponse create(ServerRequest request) throws Exception {
        DirectoryEntry entry = directory.create(request.body(DirectoryEntry.class));
        return ServerResponse.status(201).body(entry);
    }

    ServerResponse ping(ServerRequest request) {
        return ServerResponse.ok().body("pong");
    }

    ServerResponse spaIndex(ServerRequest request) {
        return ServerResponse.ok().render("index");
    }
}
