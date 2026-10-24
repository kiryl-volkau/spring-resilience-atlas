package com.acme.care.billing.ledger;

import java.time.Instant;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

@Component
public class LedgerReconciliationJob implements Job {

    private final LedgerPostingRepository postingRepository;

    public LedgerReconciliationJob(LedgerPostingRepository postingRepository) {
        this.postingRepository = postingRepository;
    }

    @Override
    public void execute(JobExecutionContext context) {
        for (LedgerPosting p : postingRepository.findUnsynced()) {
            p.markSynced(Instant.now());
            postingRepository.save(p);
        }
    }
}
