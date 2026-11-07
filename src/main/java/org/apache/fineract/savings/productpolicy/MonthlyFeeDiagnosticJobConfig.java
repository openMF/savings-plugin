/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.jobs.service.jobname.JobNameData;
import org.apache.fineract.infrastructure.jobs.service.jobname.JobNameProvider;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Native Spring Batch adapter only. No m_job row, cron, automatic month selection or financial
 * schedule is installed. The native JobStarter must establish tenant, business date and principal.
 */
@Configuration
@RequiredArgsConstructor
public class MonthlyFeeDiagnosticJobConfig {
  public static final String NAME = "SAVINGS_MONTHLY_FEE_DIAGNOSTIC";
  private final MonthlyFeeAssessmentService service;

  @Bean
  public JobNameProvider monthlyFeeDiagnosticJobNameProvider() {
    return () -> Set.of(new JobNameData(NAME, "Savings monthly fee diagnostic"));
  }

  @Bean
  public Tasklet monthlyFeeDiagnosticTasklet() {
    return (contribution, context) -> {
      var params = context.getStepContext().getJobParameters();
      String tenant = String.valueOf(params.get("tenant"));
      Object run = params.get("runId");
      if (!MonthlyFeeAssessmentService.tenant().equals(tenant)
          || !(run instanceof String id)
          || id.isBlank())
        throw ProductPolicyValidation.invalid(
            "jobParameters", "Explicit tenant and existing runId required.");
      var result = service.processChunk(id, 100);
      return result.status().equals("IN_PROGRESS")
          ? RepeatStatus.CONTINUABLE
          : RepeatStatus.FINISHED;
    };
  }

  @Bean
  public Job savingsMonthlyFeeDiagnosticJob(
      JobRepository repository, PlatformTransactionManager transactionManager) {
    var step =
        new StepBuilder(NAME, repository)
            .tasklet(monthlyFeeDiagnosticTasklet(), transactionManager)
            .build();
    return new JobBuilder(NAME, repository).start(step).build();
  }
}
