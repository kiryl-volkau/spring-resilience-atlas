package com.acme.care.resident.web;

import java.util.UUID;

import com.acme.care.resident.service.ResidentQueryService;
import com.acme.care.resident.service.ResidentSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping(ResidentPaths.BY_ID)
public abstract class AbstractResidentController {

    protected final ResidentQueryService residents;

    protected AbstractResidentController(ResidentQueryService residents) {
        this.residents = residents;
    }

    @GetMapping("/summary")
    public ResidentSummary summary(@PathVariable UUID residentId) {
        return residents.summary(residentId);
    }

    protected void requireActive(UUID residentId) {
        residents.requireActive(residentId);
    }
}
