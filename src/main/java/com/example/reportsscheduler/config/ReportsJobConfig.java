package com.example.reportsscheduler.config;

import com.example.reportsscheduler.tasklet.DataExtractionTasklet;
import com.example.reportsscheduler.tasklet.DataTransformationTasklet;
import com.example.reportsscheduler.tasklet.ReportGenerationTasklet;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ReportsJobConfig {

    @Bean
    public Job reportsJob(JobRepository jobRepository,
                          Step dataExtractionStep,
                          Step dataTransformationStep,
                          Step reportGenerationStep) {
        return new JobBuilder("reportsJob", jobRepository)
                .start(dataExtractionStep)
                .next(dataTransformationStep)
                .next(reportGenerationStep)
                .build();
    }

    @Bean
    public Step dataExtractionStep(JobRepository jobRepository,
                                   PlatformTransactionManager transactionManager,
                                   DataExtractionTasklet tasklet) {
        return new StepBuilder("dataExtractionStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    public Step dataTransformationStep(JobRepository jobRepository,
                                       PlatformTransactionManager transactionManager,
                                       DataTransformationTasklet tasklet) {
        return new StepBuilder("dataTransformationStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    public Step reportGenerationStep(JobRepository jobRepository,
                                     PlatformTransactionManager transactionManager,
                                     ReportGenerationTasklet tasklet) {
        return new StepBuilder("reportGenerationStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
