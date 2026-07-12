/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.exchangerate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Spring configuration for BCCR exchange rate components.
 *
 * <p>This configuration provides the shared {@link RestTemplate} bean used by the BCCR web service
 * client. All tenant-specific configuration is loaded from the {@code
 * c_external_service_properties} table at runtime.
 */
@Configuration
public class BccrConfiguration {

  /**
   * Creates a RestTemplate bean for consuming the BCCR Web Service.
   *
   * @return a new RestTemplate instance
   */
  @Bean
  public RestTemplate bccrRestTemplate() {
    return new RestTemplate();
  }
}
