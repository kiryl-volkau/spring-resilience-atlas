package com.acme.care.legacy.messaging;

import com.acme.care.legacy.intake.LegacyCareRecord;
import com.acme.care.legacy.intake.LegacyIntakeService;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.stereotype.Component;

@Component
public class LegacyCareInputListener {

    private final LegacyIntakeService intake;

    public LegacyCareInputListener(LegacyIntakeService intake) {
        this.intake = intake;
    }

    @StreamListener("care-input")
    public void onCareRecord(LegacyCareRecord record) {
        intake.importRecord(record);
    }
}
