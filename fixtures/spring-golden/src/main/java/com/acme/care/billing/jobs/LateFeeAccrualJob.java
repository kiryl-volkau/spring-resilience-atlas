package com.acme.care.billing.jobs;

import java.time.LocalDate;

import com.acme.care.billing.service.LateFeeService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

@Component
@DisallowConcurrentExecution
public class LateFeeAccrualJob implements Job {

    private final LateFeeService lateFeeService;

    public LateFeeAccrualJob(LateFeeService lateFeeService) {
        this.lateFeeService = lateFeeService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            lateFeeService.accrueFor(LocalDate.now());
        } catch (RuntimeException e) {
            throw new JobExecutionException(e, true);
        }
    }
}
