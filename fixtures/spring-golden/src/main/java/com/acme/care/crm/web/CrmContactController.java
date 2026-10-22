package com.acme.care.crm.web;

import static com.acme.care.shared.api.ApiPaths.API;

import java.util.List;
import java.util.UUID;

import com.acme.care.crm.service.ContactService;
import com.acme.care.crm.service.ContactService.ContactView;
import com.acme.care.crm.service.ContactService.MergeRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(API + "/crm/contacts")
public class CrmContactController {

    private final ContactService contactService;

    public CrmContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @CrmGet
    public List<ContactView> listContacts(@RequestParam(name = "q", required = false) String query) {
        return contactService.search(query);
    }

    @CrmGet("/{contactId}")
    public ContactView getContact(@PathVariable UUID contactId) {
        return contactService.get(contactId);
    }

    @CrmPost(path = "/{contactId}/merge")
    public ContactView mergeContacts(@PathVariable UUID contactId, @RequestBody MergeRequest request) {
        return contactService.merge(contactId, request.duplicateIds());
    }
}
