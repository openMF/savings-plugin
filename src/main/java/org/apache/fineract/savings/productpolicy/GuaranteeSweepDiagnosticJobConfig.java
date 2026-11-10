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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Default-off diagnostics, no cron/m_job enrollment. Native JobStarter must initialize context. */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "fineract.savings.guarantee-sweep.diagnostics-enabled",
    havingValue = "true")
public class GuaranteeSweepDiagnosticJobConfig {
  public static final String NAME = "SAVINGS_GUARANTEE_SWEEP_DIAGNOSTIC";
  private final GuaranteeSweepDiagnosticService service;

  @Bean
  public JobNameProvider guaranteeSweepDiagnosticJobNameProvider() {
    return () -> Set.of(new JobNameData(NAME, "Savings guarantee sweep diagnostic"));
  }

  @Bean
  public Tasklet guaranteeSweepDiagnosticTasklet() {
    return (contribution, context) -> {
      var params = context.getStepContext().getJobParameters();
      if (!MonthlyFeeAssessmentService.tenant().equals(params.get("tenant"))
          || !(params.get("runId") instanceof String id)
          || id.isBlank())
        throw ProductPolicyValidation.invalid(
            "jobParameters", "Matching tenant and existing runId required.");
      var result = service.processChunk(id, 100);
      return result.state() == GuaranteeSweepData.State.PROCESSING
          ? RepeatStatus.CONTINUABLE
          : RepeatStatus.FINISHED;
    };
  }

  @Bean
  public Job savingsGuaranteeSweepDiagnosticJob(
      JobRepository repository, PlatformTransactionManager manager) {
    var step =
        new StepBuilder(NAME, repository)
            .tasklet(guaranteeSweepDiagnosticTasklet(), manager)
            .build();
    return new JobBuilder(NAME, repository).start(step).build();
  }
}
