package com.acme.care.resident.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.acme.care.resident.service.VisitService;
import com.acme.care.resident.service.VisitService.VisitRequest;
import com.acme.care.resident.service.VisitService.VisitSlot;
import com.acme.care.resident.service.VisitService.VisitView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/visits")
public class VisitBookingController {

    private final VisitService visits;

    public VisitBookingController(VisitService visits) {
        this.visits = visits;
    }

    @GetMapping("/slots")
    @PostMapping("/slots/search")
    public List<VisitSlot> slots(@RequestParam UUID residentId, @RequestParam LocalDate from) {
        return visits.freeSlots(residentId, from);
    }

    @PostMapping
    public VisitView book(@RequestBody @Valid VisitRequest request) {
        return visits.book(request);
    }
}
