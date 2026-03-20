package com.example.reportsscheduler;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBatchTest
@SpringBootTest(properties = {
        "businessDate=2026-01-15",
        "spring.batch.job.name=deleteJob"
})
class DeleteJobTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    @Qualifier("deleteJob")
    private Job deleteJob;

    @Test
    void deleteJobCompletesSuccessfully() throws Exception {
        jobLauncherTestUtils.setJob(deleteJob);
        JobExecution execution = jobLauncherTestUtils.launchJob();
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
    }
}
