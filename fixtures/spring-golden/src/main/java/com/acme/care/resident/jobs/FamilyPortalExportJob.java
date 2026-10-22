package com.acme.care.resident.jobs;

import com.acme.care.resident.service.FamilyPortalExporter;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

public class FamilyPortalExportJob implements InterruptableJob {

    private FamilyPortalExporter exporter;
    private volatile boolean interrupted;

    public void setExporter(FamilyPortalExporter exporter) {
        this.exporter = exporter;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        for (String residentId : exporter.residentsWithPortalAccess()) {
            if (interrupted) {
                return;
            }
            exporter.export(residentId);
        }
    }

    @Override
    public void interrupt() {
        interrupted = true;
    }
}
