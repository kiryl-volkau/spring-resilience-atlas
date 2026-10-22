package com.acme.care.resident.batch;

import com.acme.care.resident.batch.support.CommissionerCsvWriter;
import com.acme.care.resident.batch.support.ResidentProfileReader;
import com.acme.care.resident.domain.ResidentProfile;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ResidentExportBatchConfig {

    @Bean
    public Step exportResidentsStep(JobRepository jobRepository,
                                    PlatformTransactionManager transactionManager,
                                    ResidentProfileReader reader,
                                    CommissionerCsvWriter writer) {
        return new StepBuilder("exportResidentsStep", jobRepository)
                .<ResidentProfile, ResidentProfile>chunk(200, transactionManager)
                .reader(reader)
                .writer(writer)
                .build();
    }

    @Bean
    public Job residentExportJob(JobRepository jobRepository, Step exportResidentsStep) {
        return new JobBuilder("residentExportJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(exportResidentsStep)
                .build();
    }
}
