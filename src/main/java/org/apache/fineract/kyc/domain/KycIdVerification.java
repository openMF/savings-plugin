/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.apache.fineract.kyc.domain;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "m_client_kyc_id_verification")
public class KycIdVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonBackReference
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "kyc_decision_id", nullable = false)
    private KycDecision kycDecision;

    @Column(name = "node_id", length = 255)
    private String nodeId;

    @Column(name = "verification_status", length = 50)
    private String verificationStatus;

    @Column(name = "first_name", length = 255)
    private String firstName;

    @Column(name = "last_name", length = 255)
    private String lastName;

    @Column(name = "full_name", length = 500)
    private String fullName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "age")
    private Integer age;

    @Column(name = "nationality", length = 255)
    private String nationality;

    @Column(name = "document_number", length = 255)
    private String documentNumber;

    @Column(name = "document_type", length = 255)
    private String documentType;

    @Column(name = "issuing_state", length = 50)
    private String issuingState;

    @Column(name = "issuing_state_name", length = 255)
    private String issuingStateName;

    @Column(name = "personal_number", length = 255)
    private String personalNumber;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Column(name = "formatted_address", columnDefinition = "TEXT")
    private String formattedAddress;

    @Column(name = "parsed_address", columnDefinition = "TEXT")
    private String parsedAddress;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_on_utc", nullable = false)
    private OffsetDateTime createdOnUtc;

    @Column(name = "last_modified_by", nullable = false)
    private Long lastModifiedBy;

    @Column(name = "last_modified_on_utc", nullable = false)
    private OffsetDateTime lastModifiedOnUtc;

    protected KycIdVerification() {}

    public static KycIdVerification create(final String nodeId,
                                           final String verificationStatus,
                                           final String firstName,
                                           final String lastName,
                                           final String fullName,
                                           final LocalDate dateOfBirth,
                                           final String gender,
                                           final Integer age,
                                           final String nationality,
                                           final String documentNumber,
                                           final String documentType,
                                           final String issuingState,
                                           final String issuingStateName,
                                           final String personalNumber,
                                           final LocalDate expirationDate,
                                           final String formattedAddress,
                                           final String parsedAddress,
                                           final Long createdBy) {
        final KycIdVerification v = new KycIdVerification();
        v.nodeId = nodeId;
        v.verificationStatus = verificationStatus;
        v.firstName = firstName;
        v.lastName = lastName;
        v.fullName = fullName;
        v.dateOfBirth = dateOfBirth;
        v.gender = gender;
        v.age = age;
        v.nationality = nationality;
        v.documentNumber = documentNumber;
        v.documentType = documentType;
        v.issuingState = issuingState;
        v.issuingStateName = issuingStateName;
        v.personalNumber = personalNumber;
        v.expirationDate = expirationDate;
        v.formattedAddress = formattedAddress;
        v.parsedAddress = parsedAddress;
        v.createdBy = createdBy;
        v.lastModifiedBy = createdBy;
        final OffsetDateTime now = OffsetDateTime.now();
        v.createdOnUtc = now;
        v.lastModifiedOnUtc = now;
        return v;
    }

    public void setKycDecision(final KycDecision kycDecision) {
        this.kycDecision = kycDecision;
    }

    public Long getId() { return id; }
    public Long getKycDecisionId() {
        return kycDecision != null ? kycDecision.getId() : null;
    }
    public String getVerificationStatus() { return verificationStatus; }
    public String getFullName() { return fullName; }
    public String getDocumentNumber() { return documentNumber; }
    public String getDocumentType() { return documentType; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public LocalDate getExpirationDate() { return expirationDate; }
    public String getPersonalNumber() { return personalNumber; }
    public String getGender() { return gender; }
    public String getNationality() { return nationality; }
}