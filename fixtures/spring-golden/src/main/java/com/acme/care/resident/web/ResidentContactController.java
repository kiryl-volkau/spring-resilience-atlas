package com.acme.care.resident.web;

import java.util.List;
import java.util.UUID;

import com.acme.care.resident.service.NextOfKinService;
import com.acme.care.resident.service.NextOfKinService.Contact;
import com.acme.care.resident.service.ResidentQueryService;
import com.acme.care.resident.service.ResidentSummary;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResidentContactController extends AbstractResidentController {

    private final NextOfKinService nextOfKin;

    public ResidentContactController(ResidentQueryService residents, NextOfKinService nextOfKin) {
        super(residents);
        this.nextOfKin = nextOfKin;
    }

    @Override
    public ResidentSummary summary(UUID residentId) {
        return super.summary(residentId).withContactCount(nextOfKin.count(residentId));
    }

    @GetMapping("/contacts")
    public List<Contact> listContacts(@PathVariable UUID residentId) {
        return nextOfKin.list(residentId);
    }

    @PutMapping("/contacts/{contactId}")
    public Contact updateContact(@PathVariable UUID residentId,
                                 @PathVariable UUID contactId,
                                 @RequestBody @Valid Contact contact) {
        return nextOfKin.update(residentId, contactId, contact);
    }
}
