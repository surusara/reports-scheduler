package com.example.reportsscheduler.tasklet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReportGenerationTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerationTasklet.class);

    @Value("${businessDate:#{T(java.time.LocalDate).now().minusDays(1).toString()}}")
    private String businessDate;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        log.info("[ReportGeneration] Starting report generation for businessDate={}", businessDate);
        // TODO: Implement actual report generation logic
        log.info("[ReportGeneration] Generated 0 reports for businessDate={}", businessDate);
        return RepeatStatus.FINISHED;
    }
}
