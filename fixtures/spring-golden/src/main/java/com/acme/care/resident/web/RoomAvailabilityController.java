package com.acme.care.resident.web;

import java.util.List;

import com.acme.care.resident.service.RoomAvailabilityService;
import com.acme.care.resident.service.RoomAvailabilityService.RoomView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
public record RoomAvailabilityController(RoomAvailabilityService availability) {

    @GetMapping("/available")
    public List<RoomView> available(@RequestParam String careLevel) {
        return availability.availableFor(careLevel);
    }
}
