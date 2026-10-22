package com.acme.care.resident.jobs;

import java.util.UUID;

import com.acme.care.resident.service.CareLevelReviewService;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.scheduling.quartz.QuartzJobBean;

public class CareLevelReviewJob extends QuartzJobBean {

    private CareLevelReviewService reviewService;

    public void setReviewService(CareLevelReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        JobDataMap data = context.getMergedJobDataMap();
        UUID residentId = UUID.fromString(data.getString("residentId"));
        reviewService.openScheduledReview(residentId);
    }
}
