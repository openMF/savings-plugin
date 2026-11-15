/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.fineract.testing.support.SavingsIntegrationTestBase;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.Test;

/** Real HTTP, native client JPA and plugin JDBC persistence against a migrated Fineract tenant. */
class OfficeAddressPrecisionApiHttpIntegrationTest extends SavingsIntegrationTestBase {

  @Test
  void officeAndNativeClientCoordinatesSurviveAuthenticatedCreateUpdateAndRead() {
    assertEquals(
        "2",
        querySingleValueInDefaultTenant(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='public'"
                + " AND table_name='m_address' AND column_name IN ('latitude','longitude')"
                + " AND numeric_precision-numeric_scale>=3 AND numeric_scale>=13"));
    long type =
        JsonParser.parseString(call("GET", "/v1/client/addresses/template", null, 200).asString())
            .getAsJsonObject()
            .getAsJsonArray("addressTypeIdOptions")
            .get(0)
            .getAsJsonObject()
            .get("id")
            .getAsLong();
    long office =
        id(
            call(
                "POST",
                "/v1/offices",
                Map.of(
                    "name", "WEB-1418 " + UUID.randomUUID(),
                    "parentId", 1,
                    "openingDate", "2020-01-01",
                    "dateFormat", "yyyy-MM-dd",
                    "locale", "en"),
                200));
    String officePath = "/v2/offices/" + office + "/addresses";
    Map<String, Object> coordinates = new LinkedHashMap<>();
    coordinates.put("addressTypeId", type);
    coordinates.put("street", "WEB-1418 office");
    coordinates.put("latitude", "19.3673440111718");
    coordinates.put("longitude", "-99.05897965");
    coordinates.put("locale", "en");
    long officeAddress = id(call("POST", officePath, coordinates, 200));
    assertCoordinates(first(officePath), "19.3673440111718", "-99.05897965");
    String editPath = officePath + "/" + officeAddress;
    for (List<String> pair :
        List.of(List.of("90.0000000000000", "-180.0000000000000"), List.of("0", "0"))) {
      call(
          "PUT",
          editPath,
          Map.of("latitude", pair.get(0), "longitude", pair.get(1), "locale", "en"),
          200);
      assertCoordinates(first(officePath), pair.get(0), pair.get(1));
    }
    call("PUT", editPath, coordinates, 200);
    Map<String, Object> invalid = new LinkedHashMap<>(coordinates);
    invalid.put("latitude", "19.36734401117181");
    call("PUT", editPath, invalid, 400);
    invalid.put("latitude", "90.0000000000001");
    call("PUT", editPath, invalid, 400);
    invalid.put("latitude", "NaN");
    call("POST", officePath, invalid, 400);
    assertCoordinates(first(officePath), "19.3673440111718", "-99.05897965");

    // Enable only coordinate fields in this disposable tenant's native address test configuration.
    executeSqlInDefaultTenant(
        "UPDATE m_field_configuration SET is_enabled=true WHERE entity='ADDRESS'"
            + " AND field IN ('latitude','longitude');");
    long client =
        id(
            call(
                "POST",
                "/v1/clients",
                Map.of(
                    "officeId",
                    1,
                    "legalFormId",
                    1,
                    "firstname",
                    "WEB1418",
                    "lastname",
                    UUID.randomUUID().toString(),
                    "active",
                    false,
                    "locale",
                    "en"),
                200));
    String clientPath = "/v1/client/" + client + "/addresses";
    call(
        "POST",
        clientPath + "?type=" + type,
        Map.of(
            "street",
            "WEB-1418 client",
            "latitude",
            new BigDecimal("19.43260812"),
            "longitude",
            new BigDecimal("-99.13320912"),
            "isActive",
            true),
        200);
    JsonObject legacy = first(clientPath);
    assertCoordinates(legacy, "19.43260812", "-99.13320912");
    call(
        "PUT",
        clientPath,
        Map.of(
            "addressId",
            legacy.get("addressId").getAsLong(),
            "latitude",
            new BigDecimal("19.3673440111718"),
            "longitude",
            new BigDecimal("-99.05897965"),
            "locale",
            "en"),
        200);
    JsonObject savedClient = first(clientPath);
    assertCoordinates(savedClient, "19.3673440111718", "-99.05897965");
    assertEquals("WEB-1418 client", savedClient.get("street").getAsString());
    assertCoordinates(first(officePath), "19.3673440111718", "-99.05897965");
    call("PUT", editPath, "{\"latitude\":null,\"longitude\":null}", 200);
    JsonObject cleared = first(officePath);
    assertTrue(!cleared.has("latitude") || cleared.get("latitude").isJsonNull());
    assertTrue(!cleared.has("longitude") || cleared.get("longitude").isJsonNull());
    assertCoordinates(first(clientPath), "19.3673440111718", "-99.05897965");
  }

  private Response call(String method, String path, Object body, int status) {
    var request = SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password");
    if (body != null) request.body(body);
    Response response = request.request(method, "/fineract-provider/api" + path);
    assertEquals(status, response.statusCode(), response.asString());
    return response;
  }

  private long id(Response response) {
    return JsonParser.parseString(response.asString())
        .getAsJsonObject()
        .get("resourceId")
        .getAsLong();
  }

  private JsonObject first(String path) {
    var results = JsonParser.parseString(call("GET", path, null, 200).asString()).getAsJsonArray();
    assertEquals(1, results.size());
    return results.get(0).getAsJsonObject();
  }

  private void assertCoordinates(JsonObject json, String latitude, String longitude) {
    assertEquals(0, new BigDecimal(latitude).compareTo(json.get("latitude").getAsBigDecimal()));
    assertEquals(0, new BigDecimal(longitude).compareTo(json.get("longitude").getAsBigDecimal()));
  }
}
