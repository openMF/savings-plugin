/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.fineract.testing.support.SavingsIntegrationTestBase;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogUpdatesApiHttpIntegrationTest extends SavingsIntegrationTestBase {

  private static final String PATH =
      SavingsTestUtils.CONTEXT_PATH + "/api/v2/base-teller/catalog-updates";
  private static final String AUTHORIZED_USERNAME = "web1254-catalog-manager";
  private static final String RESTRICTED_USERNAME = "web1254-no-catalog";

  @BeforeAll
  static void seedUsersAndResetState() {
    executeSqlInDefaultTenant(
        """
        DELETE FROM m_base_teller_catalog_update_audit WHERE office_id=1;
        DELETE FROM m_base_teller_catalog_update WHERE office_id=1;

        INSERT INTO m_role (name,description,is_disabled)
        VALUES ('WEB-1254 catalog manager','Catalog update read and update role',false),
               ('WEB-1254 no catalog','Catalog update permission denial role',false)
        ON CONFLICT (name) DO NOTHING;

        INSERT INTO m_appuser (
          office_id,username,firstname,lastname,password,email,firsttime_login_remaining,
          nonexpired,nonlocked,nonexpired_credentials,enabled,last_time_password_updated,
          password_never_expires
        ) SELECT 1,%s,'WEB','Catalog',password,'web1254-catalog@example.test',false,
                 true,true,true,true,CURRENT_DATE,true
          FROM m_appuser WHERE username='mifos'
        ON CONFLICT (username) DO UPDATE SET office_id=EXCLUDED.office_id;

        INSERT INTO m_appuser (
          office_id,username,firstname,lastname,password,email,firsttime_login_remaining,
          nonexpired,nonlocked,nonexpired_credentials,enabled,last_time_password_updated,
          password_never_expires
        ) SELECT 1,%s,'WEB','Restricted',password,'web1254-restricted@example.test',false,
                 true,true,true,true,CURRENT_DATE,true
          FROM m_appuser WHERE username='mifos'
        ON CONFLICT (username) DO UPDATE SET office_id=EXCLUDED.office_id;

        INSERT INTO m_role_permission (role_id,permission_id)
        SELECT r.id,p.id FROM m_role r,m_permission p
        WHERE r.name='WEB-1254 catalog manager'
          AND p.code IN ('READ_BASE_TELLER_CATALOG_UPDATE','UPDATE_BASE_TELLER_CATALOG_UPDATE')
        ON CONFLICT DO NOTHING;

        INSERT INTO m_appuser_role (appuser_id,role_id)
        SELECT u.id,r.id FROM m_appuser u,m_role r
        WHERE u.username=%s AND r.name='WEB-1254 catalog manager'
        ON CONFLICT DO NOTHING;

        INSERT INTO m_appuser_role (appuser_id,role_id)
        SELECT u.id,r.id FROM m_appuser u,m_role r
        WHERE u.username=%s AND r.name='WEB-1254 no catalog'
        ON CONFLICT DO NOTHING;
        """,
        AUTHORIZED_USERNAME,
        RESTRICTED_USERNAME,
        AUTHORIZED_USERNAME,
        RESTRICTED_USERNAME);
  }

  @Test
  @Order(1)
  void migrationLoadsAndInitialStatusContainsExactlySupportedCategories() {
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_permission"
                + " WHERE code='READ_BASE_TELLER_CATALOG_UPDATE'"));

    given(authorizedRequest())
        .when()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("size()", equalTo(3))
        .body("category", containsInAnyOrder("GENERAL", "ACCOUNTING", "USERS"))
        .body("needsUpdate", containsInAnyOrder(true, true, true))
        .body(
            "status",
            containsInAnyOrder("UPDATE_REQUIRED", "UPDATE_REQUIRED", "UPDATE_REQUIRED"));
  }

  @Test
  @Order(2)
  void categoryStatusAndInvalidCategoryAreHandledOverHttp() {
    given(authorizedRequest())
        .when()
        .get(PATH + "/GENERAL")
        .then()
        .statusCode(200)
        .body("category", equalTo("GENERAL"))
        .body("lastUpdatedAt", equalTo(null))
        .body("needsUpdate", equalTo(true))
        .body("status", equalTo("UPDATE_REQUIRED"));

    given(authorizedRequest()).when().get(PATH + "/UNKNOWN").then().statusCode(400);
  }

  @Test
  @Order(3)
  void unauthorizedReadAndUpdateAreRejected() {
    given(restrictedRequest()).when().get(PATH).then().statusCode(403);
    given(restrictedRequest()).when().post(PATH + "/GENERAL/sync").then().statusCode(403);
  }

  @Test
  @Order(4)
  void generalRefreshValidatesLiveSourcesAndPersistsStatus() {
    final String firstSuccessfulAt =
        given(authorizedRequest())
            .when()
            .post(PATH + "/GENERAL/sync")
            .then()
            .statusCode(200)
            .body("category", equalTo("GENERAL"))
            .body("lastUpdatedAt", notNullValue())
            .body("lastAttemptAt", notNullValue())
            .body("needsUpdate", equalTo(false))
            .body("status", equalTo("CURRENT"))
            .extract()
            .path("lastUpdatedAt");

    given(authorizedRequest())
        .when()
        .get(PATH + "/GENERAL")
        .then()
        .statusCode(200)
        .body("lastUpdatedAt", equalTo(firstSuccessfulAt))
        .body("status", equalTo("CURRENT"));
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_base_teller_catalog_update"
                + " WHERE office_id=1 AND category='GENERAL'"));
  }

  @Test
  @Order(5)
  void accountingAndUsersRefreshAgainstRealSources() {
    for (final String category : List.of("ACCOUNTING", "USERS")) {
      given(authorizedRequest())
          .when()
          .post(PATH + "/" + category + "/sync")
          .then()
          .statusCode(200)
          .body("category", equalTo(category))
          .body("lastUpdatedAt", notNullValue())
          .body("needsUpdate", equalTo(false))
          .body("status", equalTo("CURRENT"));
    }
  }

  @Test
  @Order(6)
  void repeatedRefreshIsSafeAndAuditedWithAuthenticatedContext() {
    final int auditCountBefore = auditCount("GENERAL");

    given(authorizedRequest())
        .when()
        .post(PATH + "/GENERAL/sync")
        .then()
        .statusCode(200)
        .body("status", equalTo("CURRENT"));

    assertEquals(1, stateCount("GENERAL"));
    assertEquals(auditCountBefore + 1, auditCount("GENERAL"));
    assertEquals(
        AUTHORIZED_USERNAME,
        querySingleValueInDefaultTenant(
            "SELECT u.username FROM m_base_teller_catalog_update_audit a"
                + " JOIN m_appuser u ON u.id=a.performed_by"
                + " WHERE a.category='GENERAL' ORDER BY a.id DESC LIMIT 1"));
    assertEquals(
        "1",
        querySingleValueInDefaultTenant(
            "SELECT office_id FROM m_base_teller_catalog_update_audit"
                + " WHERE category='GENERAL' ORDER BY id DESC LIMIT 1"));
  }

  @Test
  @Order(7)
  void failurePreservesLastSuccessAndRetrySucceeds() {
    final String previousSuccess =
        given(authorizedRequest())
            .when()
            .get(PATH + "/GENERAL")
            .then()
            .statusCode(200)
            .extract()
            .path("lastUpdatedAt");

    executeSqlInDefaultTenant(
        "ALTER TABLE m_service_payment_denomination RENAME TO m_web1254_missing_denomination;");
    try {
      given(authorizedRequest())
          .when()
          .post(PATH + "/GENERAL/sync")
          .then()
          .statusCode(200)
          .body("status", equalTo("FAILED"))
          .body("needsUpdate", equalTo(true))
          .body("failureCode", equalTo("SOURCE_VALIDATION_FAILED"))
          .body("lastUpdatedAt", equalTo(previousSuccess));
    } finally {
      executeSqlInDefaultTenant(
          "ALTER TABLE m_web1254_missing_denomination RENAME TO m_service_payment_denomination;");
    }

    given(authorizedRequest())
        .when()
        .post(PATH + "/GENERAL/sync")
        .then()
        .statusCode(200)
        .body("status", equalTo("CURRENT"))
        .body("needsUpdate", equalTo(false))
        .body("failureCode", equalTo(null));
    assertTrue(auditCount("GENERAL") >= 3);
  }

  @Test
  @Order(8)
  void concurrentRefreshesCannotCreateDuplicateState() throws Exception {
    final int auditCountBefore = auditCount("USERS");
    final CompletableFuture<Response> first =
        CompletableFuture.supplyAsync(
            () -> given(authorizedRequest()).when().post(PATH + "/USERS/sync"));
    final CompletableFuture<Response> second =
        CompletableFuture.supplyAsync(
            () -> given(authorizedRequest()).when().post(PATH + "/USERS/sync"));

    final Response firstResponse = first.get(30, TimeUnit.SECONDS);
    final Response secondResponse = second.get(30, TimeUnit.SECONDS);
    assertEquals(200, firstResponse.statusCode());
    assertEquals(200, secondResponse.statusCode());
    assertTrue(
        List.of("CURRENT", "UPDATING").contains(firstResponse.jsonPath().getString("status")));
    assertTrue(
        List.of("CURRENT", "UPDATING").contains(secondResponse.jsonPath().getString("status")));
    assertEquals(1, stateCount("USERS"));
    assertEquals(auditCountBefore + 2, auditCount("USERS"));
  }

  private static io.restassured.specification.RequestSpecification authorizedRequest() {
    return given(
        SavingsTestUtils.requestSpecWithAuth(
            getFineractPort(), AUTHORIZED_USERNAME, "password"));
  }

  private static io.restassured.specification.RequestSpecification restrictedRequest() {
    return given(
        SavingsTestUtils.requestSpecWithAuth(
            getFineractPort(), RESTRICTED_USERNAME, "password"));
  }

  private static int stateCount(final String category) {
    return Integer.parseInt(
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_base_teller_catalog_update"
                + " WHERE office_id=1 AND category='"
                + category
                + "'"));
  }

  private static int auditCount(final String category) {
    return Integer.parseInt(
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM m_base_teller_catalog_update_audit"
                + " WHERE office_id=1 AND category='"
                + category
                + "'"));
  }
}
