package com.acme.care.legacy.messaging;

import com.acme.care.legacy.lab.LabResultImporter;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
public class LegacyLabResultListener {

    private final LabResultImporter importer;

    public LegacyLabResultListener(LabResultImporter importer) {
        this.importer = importer;
    }

    @JmsListener(destination = "LEGACY.LAB.RESULTS", containerFactory = "legacyJmsListenerContainerFactory")
    public void onLabResult(TextMessage message) throws JMSException {
        importer.importResult(message.getJMSMessageID(), message.getText());
    }
}
