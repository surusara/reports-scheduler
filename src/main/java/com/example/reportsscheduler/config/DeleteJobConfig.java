package com.example.reportsscheduler.config;

import com.example.reportsscheduler.tasklet.DataCleanupTasklet;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class DeleteJobConfig {

    @Bean
    public Job deleteJob(JobRepository jobRepository, Step dataCleanupStep) {
        return new JobBuilder("deleteJob", jobRepository)
                .start(dataCleanupStep)
                .build();
    }

    @Bean
    public Step dataCleanupStep(JobRepository jobRepository,
                                PlatformTransactionManager transactionManager,
                                DataCleanupTasklet tasklet) {
        return new StepBuilder("dataCleanupStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
