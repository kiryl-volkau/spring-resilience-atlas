package com.acme.care.legacy.batch;

import java.time.LocalDate;

import com.acme.care.legacy.archive.ArchiveStore;
import org.springframework.stereotype.Component;

@Component
public class ArchivePurgeJob implements Job {

    private static final int RETENTION_YEARS = 8;

    private final ArchiveStore archiveStore;

    public ArchivePurgeJob(ArchiveStore archiveStore) {
        this.archiveStore = archiveStore;
    }

    @Override
    public String name() {
        return "archive-purge";
    }

    @Override
    public void execute(JobContext context) {
        LocalDate cutoff = context.businessDate().minusYears(RETENTION_YEARS);
        int purged = archiveStore.purgeDischargedResidentsBefore(cutoff);
        context.report("purged", purged);
    }
}
