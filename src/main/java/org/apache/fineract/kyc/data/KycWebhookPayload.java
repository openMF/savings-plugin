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
import lombok.Getter;
import lombok.Setter;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class KycWebhookPayload {

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

    // Verification decision details. Nullable if the session is still in progress
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
        private String createdAt; // ISO Date String

        private List<String> features;

        @JsonProperty("face_matches")
        private List<FaceMatch> faceMatches;

        @JsonProperty("id_verifications")
        private List<IdVerification> idVerifications;

        @JsonProperty("aml_screenings")
        private List<AmlScreening> amlScreenings;

        /** Proof-of-Address verifications */
        @JsonProperty("poa_verifications")
        private List<PoaVerification> poaVerifications;
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
        @JsonProperty("document_number")
        private String documentNumber;
        @JsonProperty("document_type")
        private String documentType;
        @JsonProperty("issuing_state")
        private String issuingState;
        @JsonProperty("issuing_state_name")
        private String issuingStateName;
        private String nationality;
        private String gender;
        private Integer age;
        @JsonProperty("personal_number")
        private String personalNumber;
        @JsonProperty("expiration_date")
        private String expirationDate;
        @JsonProperty("formatted_address")
        private String formattedAddress;
        @JsonProperty("parsed_address")
        private ParsedAddress parsedAddress;
    }

    // -------------------------------------------------------------------------
    // ParsedAddress (shared)
    // -------------------------------------------------------------------------
    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParsedAddress {
        private String city;
        @JsonProperty("postal_code")
        private String postalCode;
        private String region;
        @JsonProperty("street_1")
        private String street1;
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
        @JsonProperty("screened_data")
        private ScreenedData screenedData;
        private List<AmlHit> hits;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
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
    // PoaVerification (NEW)
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

    // -------------------------------------------------------------------------
    // PoaDocumentMetadata
    // -------------------------------------------------------------------------
    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PoaDocumentMetadata {

        @JsonProperty("content_type")
        private String contentType;

        @JsonProperty("creation_date")
        private String creationDate;

        private String creator;

        private String encryption;

        @JsonProperty("exif_digitized_date")
        private String exifDigitizedDate;

        @JsonProperty("exif_original_date")
        private String exifOriginalDate;

        @JsonProperty("file_size")
        private Long fileSize;

        @JsonProperty("has_different_creation_mod_date")
        private Boolean hasDifferentCreationModDate;

        @JsonProperty("image_anomalies")
        private ImageAnomalies imageAnomalies;

        @JsonProperty("is_signed")
        private Boolean isSigned;

        @JsonProperty("is_tampered")
        private Boolean isTampered;

        @JsonProperty("modified_date")
        private String modifiedDate;

        @JsonProperty("overlay_manipulation")
        private OverlayManipulation overlayManipulation;

        @JsonProperty("processed_by_known_editor")
        private String processedByKnownEditor;

        private String producer;

        @JsonProperty("signature_info")
        private Object signatureInfo;

        private String software;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageAnomalies {
        private Boolean analyzed;
        private Boolean detected;
        @JsonProperty("near_duplicate_images")
        private List<Object> nearDuplicateImages;
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

    // -------------------------------------------------------------------------
    // PoaExtraFields
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // PoaWarning
    // -------------------------------------------------------------------------
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
}