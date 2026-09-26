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
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Didit verification webhook payload.
 *
 * <p>Supports both person KYC and company KYB sessions. Company flows typically include {@code
 * QUESTIONNAIRE}, {@code EMAIL_VERIFICATION}, plus legal-representative {@code ID_VERIFICATION} /
 * {@code FACE_MATCH} / {@code AML}.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class KycWebhookPayload {

  @JsonProperty("application_id")
  private String applicationId;

  @JsonProperty("created_at")
  private Long createdAt;

  private Map<String, Object> metadata;

  @JsonProperty("session_id")
  private String sessionId;

  private String status;

  private Long timestamp;

  @JsonProperty("webhook_type")
  private String webhookType;

  @JsonProperty("workflow_id")
  private String workflowId;

  @JsonProperty("workflow_version")
  private Integer workflowVersion;

  @JsonProperty("event_id")
  private String eventId;

  private String environment;

  @JsonProperty("vendor_data")
  private String vendorData;

  @JsonProperty("sandbox_scenario")
  private String sandboxScenario;

  /** Verification decision details. Nullable if the session is still in progress. */
  private Decision decision;

  // -------------------------------------------------------------------------
  // Decision
  // -------------------------------------------------------------------------
  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Decision {

    private String status;

    @JsonProperty("workflow_id")
    private String workflowId;

    @JsonProperty("created_at")
    private String createdAt;

    private List<String> features;

    @JsonProperty("face_matches")
    private List<FaceMatch> faceMatches;

    @JsonProperty("id_verifications")
    private List<IdVerification> idVerifications;

    @JsonProperty("aml_screenings")
    private List<AmlScreening> amlScreenings;

    @JsonProperty("poa_verifications")
    private List<PoaVerification> poaVerifications;

    /** KYB / company questionnaires (Didit QUESTIONNAIRE feature). */
    private List<Questionnaire> questionnaires;

    /** Email OTP verification steps (Didit EMAIL_VERIFICATION feature). */
    @JsonProperty("email_verifications")
    private List<EmailVerification> emailVerifications;

    @JsonProperty("vendor_data")
    private String vendorData;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("session_url")
    private String sessionUrl;

    @JsonProperty("session_number")
    private Integer sessionNumber;

    private String environment;

    private String callback;

    @JsonProperty("contact_details")
    private Object contactDetails;
  }

  // -------------------------------------------------------------------------
  // Questionnaire (KYB company form)
  // -------------------------------------------------------------------------
  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Questionnaire {

    @JsonProperty("node_id")
    private String nodeId;

    private String status;

    private String title;

    private Integer version;

    private List<QuestionnaireSection> sections;

    private List<Object> warnings;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class QuestionnaireSection {

    private String title;

    private String description;

    private List<QuestionnaireItem> items;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class QuestionnaireItem {

    private String uuid;

    private String title;

    private String description;

    @JsonProperty("element_type")
    private String elementType;

    @JsonProperty("is_required")
    private Boolean isRequired;

    private String placeholder;

    private QuestionnaireAnswer answer;

    private List<QuestionnaireChoice> choices;

    private Object value;

    @JsonProperty("max_files")
    private Integer maxFiles;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class QuestionnaireAnswer {

    /** Scalar answers (string, number, boolean, or nested phone object). */
    private Object value;

    /** Uploaded file URLs for FILE_UPLOAD elements. */
    private List<String> files;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class QuestionnaireChoice {

    private String id;

    private String label;

    private String value;
  }

  // -------------------------------------------------------------------------
  // EmailVerification
  // -------------------------------------------------------------------------
  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class EmailVerification {

    @JsonProperty("node_id")
    private String nodeId;

    private String status;

    private String email;

    @JsonProperty("is_breached")
    private Boolean isBreached;

    @JsonProperty("is_disposable")
    private Boolean isDisposable;

    @JsonProperty("is_undeliverable")
    private Boolean isUndeliverable;

    @JsonProperty("verification_attempts")
    private Integer verificationAttempts;

    @JsonProperty("verified_at")
    private String verifiedAt;

    private List<Object> breaches;

    private List<Object> matches;

    private List<Object> warnings;

    private List<EmailLifecycleEvent> lifecycle;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class EmailLifecycleEvent {

    private String type;

    private BigDecimal fee;

    private String timestamp;

    private Map<String, Object> details;
  }

  // -------------------------------------------------------------------------
  // FaceMatch
  // -------------------------------------------------------------------------
  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class FaceMatch {

    @JsonProperty("node_id")
    private String nodeId;

    private BigDecimal score;

    private String status;

    @JsonProperty("source_image")
    private String sourceImage;

    @JsonProperty("target_image")
    private String targetImage;

    @JsonProperty("source_image_session_id")
    private String sourceImageSessionId;

    private List<Object> warnings;
  }

  // -------------------------------------------------------------------------
  // IdVerification
  // -------------------------------------------------------------------------
  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class IdVerification {

    @JsonProperty("node_id")
    private String nodeId;

    private String status;

    @JsonProperty("first_name")
    private String firstName;

    @JsonProperty("last_name")
    private String lastName;

    @JsonProperty("full_name")
    private String fullName;

    @JsonProperty("date_of_birth")
    private String dateOfBirth;

    private String gender;

    private Integer age;

    private String nationality;

    @JsonProperty("document_number")
    private String documentNumber;

    @JsonProperty("document_type")
    private String documentType;

    @JsonProperty("document_subtype")
    private String documentSubtype;

    @JsonProperty("issuing_state")
    private String issuingState;

    @JsonProperty("issuing_state_name")
    private String issuingStateName;

    @JsonProperty("personal_number")
    private String personalNumber;

    @JsonProperty("expiration_date")
    private String expirationDate;

    @JsonProperty("date_of_issue")
    private String dateOfIssue;

    @JsonProperty("formatted_address")
    private String formattedAddress;

    @JsonProperty("parsed_address")
    private ParsedAddress parsedAddress;

    private String address;

    @JsonProperty("front_image")
    private String frontImage;

    @JsonProperty("back_image")
    private String backImage;

    @JsonProperty("extra_fields")
    private Map<String, Object> extraFields;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ParsedAddress {

    private String city;

    private String street;

    @JsonProperty("house_number")
    private String houseNumber;

    @JsonProperty("postal_code")
    private String postalCode;

    private String region;

    private String country;
  }

  // -------------------------------------------------------------------------
  // AmlScreening
  // -------------------------------------------------------------------------
  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class AmlScreening {

    @JsonProperty("node_id")
    private String nodeId;

    private String status;

    @JsonProperty("total_hits")
    private Integer totalHits;

    /** person | company — company KYB may screen the legal rep as person. */
    @JsonProperty("entity_type")
    private String entityType;

    @JsonProperty("screened_data")
    private ScreenedData screenedData;

    private List<AmlHit> hits;

    private List<Object> warnings;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ScreenedData {

    @JsonProperty("full_name")
    private String fullName;

    @JsonProperty("document_number")
    private String documentNumber;

    @JsonProperty("personal_number")
    private String personalNumber;

    @JsonProperty("document_type")
    private String documentType;

    @JsonProperty("date_of_birth")
    private String dateOfBirth;

    private String nationality;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class AmlHit {

    private String id;

    private String caption;

    @JsonProperty("match_score")
    private Integer matchScore;

    @JsonProperty("review_status")
    private String reviewStatus;

    @JsonProperty("risk_score")
    private Integer riskScore;

    private List<String> datasets;

    @JsonProperty("additional_information")
    private Map<String, Object> additionalInformation;
  }

  // -------------------------------------------------------------------------
  // PoaVerification
  // -------------------------------------------------------------------------
  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PoaVerification {

    @JsonProperty("node_id")
    private String nodeId;

    private String status;

    @JsonProperty("document_file")
    private String documentFile;

    @JsonProperty("document_language")
    private String documentLanguage;

    @JsonProperty("document_type")
    private String documentType;

    @JsonProperty("document_subtype")
    private String documentSubtype;

    @JsonProperty("document_metadata")
    private PoaDocumentMetadata documentMetadata;

    @JsonProperty("issue_date")
    private String issueDate;

    @JsonProperty("expiration_date")
    private String expirationDate;

    private String issuer;

    @JsonProperty("issuing_state")
    private String issuingState;

    @JsonProperty("name_on_document")
    private String nameOnDocument;

    @JsonProperty("name_match_score_id_verification")
    private Integer nameMatchScoreIdVerification;

    @JsonProperty("name_match_score_expected_details")
    private Integer nameMatchScoreExpectedDetails;

    @JsonProperty("poa_address")
    private String poaAddress;

    @JsonProperty("poa_formatted_address")
    private String poaFormattedAddress;

    @JsonProperty("poa_parsed_address")
    private ParsedAddress poaParsedAddress;

    @JsonProperty("expected_details_address")
    private String expectedDetailsAddress;

    @JsonProperty("expected_details_formatted_address")
    private String expectedDetailsFormattedAddress;

    @JsonProperty("expected_details_parsed_address")
    private ParsedAddress expectedDetailsParsedAddress;

    @JsonProperty("extra_fields")
    private PoaExtraFields extraFields;

    @JsonProperty("extra_files")
    private List<Object> extraFiles;

    private List<PoaWarning> warnings;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PoaDocumentMetadata {

    @JsonProperty("page_count")
    private Integer pageCount;

    @JsonProperty("file_size")
    private Long fileSize;

    @JsonProperty("mime_type")
    private String mimeType;

    @JsonProperty("image_anomalies")
    private ImageAnomalies imageAnomalies;

    @JsonProperty("overlay_manipulation")
    private OverlayManipulation overlayManipulation;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ImageAnomalies {

    private Boolean analyzed;

    private Boolean detected;

    private List<Object> signals;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OverlayManipulation {

    private Boolean analyzed;

    private Boolean detected;

    @JsonProperty("duplicate_font_subsets")
    private List<Object> duplicateFontSubsets;

    @JsonProperty("fragmented_fonts")
    private List<Object> fragmentedFonts;

    @JsonProperty("manipulated_blocks")
    private List<Object> manipulatedBlocks;

    @JsonProperty("manipulated_regions")
    private List<Object> manipulatedRegions;

    @JsonProperty("page_furniture_suppressed")
    private Object pageFurnitureSuppressed;

    private List<Object> signals;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PoaExtraFields {

    @JsonProperty("additional_names")
    private List<String> additionalNames;

    @JsonProperty("bank_account_number")
    private String bankAccountNumber;

    @JsonProperty("bank_branch_address")
    private String bankBranchAddress;

    @JsonProperty("bank_branch_name")
    private String bankBranchName;

    @JsonProperty("bank_iban")
    private String bankIban;

    @JsonProperty("bank_routing_number")
    private String bankRoutingNumber;

    @JsonProperty("bank_sort_code")
    private String bankSortCode;

    @JsonProperty("bank_swift_bic")
    private String bankSwiftBic;

    @JsonProperty("document_phone_number")
    private String documentPhoneNumber;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PoaWarning {

    @JsonProperty("additional_data")
    private Map<String, Object> additionalData;

    private String feature;

    @JsonProperty("log_type")
    private String logType;

    @JsonProperty("long_description")
    private String longDescription;

    @JsonProperty("node_id")
    private String nodeId;

    private String risk;

    @JsonProperty("short_description")
    private String shortDescription;
  }

  /**
   * Heuristic: company KYB when features include QUESTIONNAIRE or questionnaires are present.
   */
  public boolean isCompanyVerification() {
    if (decision == null) {
      return false;
    }
    if (decision.getQuestionnaires() != null && !decision.getQuestionnaires().isEmpty()) {
      return true;
    }
    if (decision.getFeatures() != null) {
      return decision.getFeatures().stream()
          .anyMatch(f -> f != null && "QUESTIONNAIRE".equalsIgnoreCase(f.trim()));
    }
    return false;
  }
}