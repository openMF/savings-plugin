/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KycWebhookPayload {

  private Object metadata;

  private String status;

  private Long timestamp;

  private Decision decision;

  @JsonProperty("created_at")
  private Long createdAt;

  @JsonProperty("session_id")
  private String sessionId;

  @JsonProperty("webhook_type")
  private String webhookType;

  @JsonProperty("workflow_id")
  private String workflowId;

  @JsonProperty("workflow_version")
  private Integer workflowVersion;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Decision {

    private String status;

    private List<String> features;

    @JsonProperty("workflow_id")
    private String workflowId;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("face_matches")
    private List<FaceMatch> faceMatches;

    @JsonProperty("id_verifications")
    private List<IdVerification> idVerifications;

    @JsonProperty("aml_screenings")
    private List<AmlScreening> amlScreenings;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class FaceMatch {

    private BigDecimal score;

    private String status;

    @JsonProperty("node_id")
    private String nodeId;

    @JsonProperty("source_image")
    private String sourceImage;

    @JsonProperty("target_image")
    private String targetImage;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class IdVerification {

    private String status;

    private String nationality;

    private String gender;

    private Integer age;

    @JsonProperty("node_id")
    private String nodeId;

    @JsonProperty("first_name")
    private String firstName;

    @JsonProperty("last_name")
    private String lastName;

    @JsonProperty("full_name")
    private String fullName;

    @JsonProperty("date_of_birth")
    private String dateOfBirth;

    @JsonProperty("document_number")
    private String documentNumber;

    @JsonProperty("document_type")
    private String documentType;

    @JsonProperty("issuing_state")
    private String issuingState;

    @JsonProperty("issuing_state_name")
    private String issuingStateName;

    @JsonProperty("personal_number")
    private String personalNumber;

    @JsonProperty("expiration_date")
    private String expirationDate;

    @JsonProperty("formatted_address")
    private String formattedAddress;

    @JsonProperty("parsed_address")
    private Object parsedAddress;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class AmlScreening {

    private String status;

    private List<Object> hits;

    @JsonProperty("node_id")
    private String nodeId;

    @JsonProperty("total_hits")
    private Integer totalHits;

    private ScreenedData screenedData;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScreenedData {
      private String nationality;
      private String fullName;

      @JsonProperty("document_number")
      private String documentNumber;

      @JsonProperty("date_of_birth")
      private String dateOfBirth;
    }
  }
}
