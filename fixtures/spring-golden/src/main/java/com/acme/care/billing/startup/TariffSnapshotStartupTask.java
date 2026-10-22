package com.acme.care.billing.startup;

import com.acme.care.billing.service.TariffSnapshotService;
import com.acme.care.shared.startup.AbstractStartupTask;
import org.springframework.stereotype.Component;

@Component
public class TariffSnapshotStartupTask extends AbstractStartupTask {

    private final TariffSnapshotService snapshots;

    public TariffSnapshotStartupTask(TariffSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    protected void execute() {
        snapshots.captureCurrentTariffs();
    }
}
