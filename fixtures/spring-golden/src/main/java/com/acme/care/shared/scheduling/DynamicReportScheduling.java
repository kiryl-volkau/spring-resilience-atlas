package com.acme.care.shared.scheduling;

import java.time.ZoneId;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

@Configuration
public class DynamicReportScheduling implements SchedulingConfigurer {

    private final ReportScheduleRepository scheduleRepository;
    private final ReportRunner reportRunner;

    public DynamicReportScheduling(ReportScheduleRepository scheduleRepository, ReportRunner reportRunner) {
        this.scheduleRepository = scheduleRepository;
        this.reportRunner = reportRunner;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        scheduleRepository.findAllByEnabledTrue().forEach(schedule ->
                registrar.addTriggerTask(
                        () -> reportRunner.run(schedule.getReportCode()),
                        new CronTrigger(schedule.getCron(), ZoneId.of(schedule.getZone()))));
    }
}
