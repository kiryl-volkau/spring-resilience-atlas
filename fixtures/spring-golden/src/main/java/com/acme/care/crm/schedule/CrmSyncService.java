package com.acme.care.crm.schedule;

import com.acme.care.crm.events.ContactMergedEvent;
import com.acme.care.crm.service.LeadScoringService;
import com.acme.care.crm.service.MarketingPlatformGateway;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CrmSyncService {

    private final MarketingPlatformGateway marketingPlatform;
    private final LeadScoringService leadScoring;

    public CrmSyncService(MarketingPlatformGateway marketingPlatform, LeadScoringService leadScoring) {
        this.marketingPlatform = marketingPlatform;
        this.leadScoring = leadScoring;
    }

    @Scheduled(fixedDelayString = "${care.crm.sync-delay:60000}")
    public void pullMarketingContacts() {
        marketingPlatform.pullChangedContactsSinceLastCursor();
    }

    @Scheduled(cron = "0 0 6 * * MON-FRI")
    @Scheduled(cron = "0 0 9 * * SAT,SUN")
    public void recalculateLeadScores() {
        leadScoring.recalculateAll();
    }

    @Async
    @EventListener
    public void onContactMerged(ContactMergedEvent event) {
        marketingPlatform.pushMerge(event.survivingContactId(), event.mergedContactIds());
    }
}
