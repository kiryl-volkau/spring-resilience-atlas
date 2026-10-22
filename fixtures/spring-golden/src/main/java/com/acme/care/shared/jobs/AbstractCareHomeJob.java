package com.acme.care.shared.jobs;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

@DisallowConcurrentExecution
public abstract class AbstractCareHomeJob implements Job {

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String homeCode = context.getMergedJobDataMap().getString("homeCode");
        if (homeCode == null) {
            throw new JobExecutionException("homeCode missing from job data");
        }
        runForHome(homeCode, context);
    }

    protected abstract void runForHome(String homeCode, JobExecutionContext context) throws JobExecutionException;
}
