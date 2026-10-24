package com.acme.care.crm.web;

import static com.acme.care.shared.api.ApiPaths.API;

import java.util.UUID;

import com.acme.care.shared.messaging.KafkaTopics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(API + "/crm/contacts")
public class CrmContactSyncController {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public CrmContactSyncController(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping("/{contactId}/sync")
    public void syncContact(@PathVariable UUID contactId, @RequestBody String contact) throws Exception {
        kafkaTemplate.send(KafkaTopics.CONTACT_UPDATES, contactId.toString(), contact).get();
    }
}
