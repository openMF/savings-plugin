/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.fastpayment.sinpe.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.fastpayment.sinpe.data.SinpeParticipantData;
import org.apache.fineract.fastpayment.sinpe.service.SinpeParticipantReadPlatformService;
import org.apache.fineract.fastpayment.sinpe.service.SinpeParticipantWritePlatformService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

@Path("/v1/sinpe/participants")
@Component
@Tag(name = "SINPE Participants", description = "Administración de códigos de participantes del Sistema Nacional de Pagos Electrónicos (BCCR)")
@RequiredArgsConstructor
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SinpeParticipantApiResource {

    private final SinpeParticipantReadPlatformService readService;
    private final SinpeParticipantWritePlatformService writeService;

    @GET
    @Operation(summary = "List all SINPE participants",
            description = "Returns the list of SINPE participants. Can be filtered by category and active status.")
    public Collection<SinpeParticipantData> retrieveAll(
            @Parameter(description = "Filter by category (BANCOS, COOPERATIVAS, FONDOS, etc.)")
            @QueryParam("category") final String category,
            @Parameter(description = "Return only active participants")
            @QueryParam("activeOnly") @DefaultValue("true") final boolean activeOnly) {
        return readService.retrieveAll(category, activeOnly);
    }

    @GET
    @Path("{code}")
    @Operation(summary = "Retrieve a SINPE participant by code")
    public SinpeParticipantData retrieveByCode(
            @Parameter(description = "SINPE participant code (e.g. 0100, 0151)")
            @PathParam("code") final String code) {
        return readService.retrieveByCode(code);
    }

    @GET
    @Path("legal-id/{legalId}")
    @Operation(summary = "Retrieve a SINPE participant by Cédula Jurídica")
    public SinpeParticipantData retrieveByLegalId(
            @Parameter(description = "Cédula Jurídica (e.g. 4-000-004017)")
            @PathParam("legalId") final String legalId) {
        return readService.retrieveByLegalId(legalId);
    }

    @POST
    @Operation(summary = "Create a new SINPE participant")
    public Response create(final Map<String, Object> request) {
        final Long resourceId = writeService.create(request);
        return Response.status(Response.Status.CREATED)
                .entity(Map.of("resourceId", resourceId, "resourceIdentifier", request.get("code")))
                .build();
    }

    @PUT
    @Path("{code}")
    @Operation(summary = "Update an existing SINPE participant")
    public Response update(
            @PathParam("code") final String code,
            final Map<String, Object> request) {
        writeService.update(code, request);
        return Response.ok(Map.of("resourceId", code)).build();
    }

    @DELETE
    @Path("{code}")
    @Operation(summary = "Deactivate a SINPE participant (soft delete)")
    public Response deactivate(@PathParam("code") final String code) {
        writeService.deactivate(code);
        return Response.ok(Map.of("resourceId", code, "status", "deactivated")).build();
    }
}