package com.bank.upgrade.ingestion.job;

import com.bank.upgrade.ingestion.config.IngestionProperties;
import com.bank.upgrade.ingestion.domain.AccountStagingRepository;
import com.bank.upgrade.ingestion.domain.IngestionErrorRepository;
import com.bank.upgrade.ingestion.excel.ExcelRowReader;
import com.bank.upgrade.ingestion.excel.RawRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashSet;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * One job, one step: read the downloaded .xlsx row by row, check each row, save chunks.
 * <p>
 * {@code fileId} is the only identifying parameter, so each file is one job instance. Launching it again after a
 * failure restarts that instance: Spring Batch reopens the step with its saved context and the reader resumes after
 * the last committed chunk. {@code path} is not identifying because each attempt downloads to a new temporary file.
 */
@Configuration(proxyBeanMethods = false)
public class IngestionJobConfig {

    public static final String JOB_NAME = "ingestAccountsJob";

    @Bean
    Job ingestAccountsJob(JobRepository jobRepository, Step loadAccountsStep) {
        return new JobBuilder(JOB_NAME, jobRepository).start(loadAccountsStep).build();
    }

    @Bean
    Step loadAccountsStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            IngestionProperties properties,
            ExcelRowReader excelReader,
            RowProcessor rowProcessor,
            RowWriter rowWriter) {
        return new StepBuilder("loadAccounts", jobRepository)
            .<RawRow, ParsedRow>chunk(properties.chunkSize(), transactionManager)
            .reader(excelReader)
            .processor(rowProcessor)
            .writer(rowWriter)
            .build();
    }

    @Bean
    @StepScope
    ExcelRowReader excelReader(
            @Value("#{jobParameters['path']}") String path) {
        return new ExcelRowReader(Path.of(path));
    }

    @Bean
    @StepScope
    RowProcessor rowProcessor(
            @Value("#{jobParameters['fileId']}") Long fileId, AccountStagingRepository staging, ObjectMapper mapper) {
        // Rows saved by an earlier attempt of this file, so a restart still catches duplicates
        return new RowProcessor(fileId, new HashSet<>(staging.findAccountNosByFileId(fileId)), mapper);
    }

    @Bean
    @StepScope
    RowWriter rowWriter(AccountStagingRepository staging, IngestionErrorRepository errors) {
        return new RowWriter(staging, errors);
    }
}
