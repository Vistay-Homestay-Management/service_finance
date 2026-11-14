package com.vistay.finance.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vistay.finance.application.FinanceAuthorizationClient;
import com.vistay.finance.application.FinanceAuthorizationClient.PartitionBinding;
import com.vistay.finance.application.FinanceAuthorizationClient.Scope;
import com.vistay.finance.application.FinanceAuthorizationClient.Snapshot;
import com.vistay.finance.application.FinanceBookingAuthorizationClient;
import com.vistay.finance.application.FinanceBookingAuthorizationClient.BookingRecord;
import com.vistay.finance.application.FinancePropertyPartitionClient;
import com.vistay.finance.application.FinancePropertyPartitionClient.Resolution;
import com.vistay.finance.application.dto.AmendmentProposalSnapshot;
import com.vistay.finance.application.dto.AssessReservationAmendmentCommand;
import com.vistay.finance.application.dto.ReservationAmendmentAssessmentResponse;
import com.vistay.finance.application.service.ReservationAmendmentAssessmentService;
import com.vistay.finance.application.service.ReservationAmendmentAssessmentService.AuthoritativeScopeContext;
import com.vistay.finance.domain.model.AmendmentAssessmentStatus;
import com.vistay.finance.domain.model.AmendmentRequiredAction;
import com.vistay.finance.domain.model.ReservationAmendmentAssessment;
import com.vistay.finance.domain.model.ReservationFieldGroup;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class ReservationAmendmentAssessmentAuthorizationTest {

    private final FinanceAuthorizationClient authorizationClient = mock(FinanceAuthorizationClient.class);
    private final FinanceBookingAuthorizationClient bookingClient = mock(FinanceBookingAuthorizationClient.class);
    private final FinancePropertyPartitionClient propertyPartitionClient = mock(FinancePropertyPartitionClient.class);
    private final FinanceOperationalAuthorization authorization =
        new FinanceOperationalAuthorization(authorizationClient, bookingClient, propertyPartitionClient);

    private final ReservationAmendmentAssessmentService assessmentService = mock(ReservationAmendmentAssessmentService.class);
    private final ReservationAmendmentAssessmentController controller =
        new ReservationAmendmentAssessmentController(assessmentService, authorization);

    private final Jwt jwt = mock(Jwt.class);
    private final UUID accountId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(jwt.getSubject()).thenReturn(accountId.toString());
        when(assessmentService.resolveOrVerifyAssessScope(any(), any(), any()))
            .thenReturn(new AuthoritativeScopeContext(propertyId, customerId));
        when(assessmentService.resolveAuthoritativeScope(any(), any()))
            .thenReturn(new AuthoritativeScopeContext(propertyId, customerId));
    }

    @Test
    @DisplayName("Assess amendment: requires RESERVATION_AMENDMENT_ASSESS permission and passes ALL scope")
    void assessAmendment_withAllScope_succeeds() {
        when(authorizationClient.require(accountId, FinanceOperationalAuthorization.RESERVATION_AMENDMENT_ASSESS))
            .thenReturn(new Snapshot(true, profileId, 1L, Map.of(
                FinanceOperationalAuthorization.RESERVATION_AMENDMENT_ASSESS, Set.of(Scope.ALL)
            ), Map.of()));

        var before = dummySnapshot();
        var after = dummySnapshot();
        var cmd = new AssessReservationAmendmentCommand(
            bookingId, "prop-1", "DEP-1", before, after,
            Instant.now().plusSeconds(600), "idem-ctrl-1", "corr-1", null, null
        );

        ReservationAmendmentAssessmentResponse dummyRes = dummyResponse();
        when(assessmentService.assessAmendment(any(), eq(profileId), any())).thenReturn(dummyRes);

        var res = controller.assessAmendment(jwt, cmd, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(bookingClient, never()).get(any());
        verify(propertyPartitionClient, never()).resolve(any());
    }

    @Test
    @DisplayName("Assess amendment: ASSIGNED scope checks booking attribution")
    void assessAmendment_withAssignedScope_checksAssignment() {
        when(authorizationClient.require(accountId, FinanceOperationalAuthorization.RESERVATION_AMENDMENT_ASSESS))
            .thenReturn(new Snapshot(true, profileId, 1L, Map.of(
                FinanceOperationalAuthorization.RESERVATION_AMENDMENT_ASSESS, Set.of(Scope.ASSIGNED)
            ), Map.of()));

        when(bookingClient.get(bookingId)).thenReturn(new BookingRecord(bookingId, propertyId, Set.of(profileId)));

        var cmd = new AssessReservationAmendmentCommand(
            bookingId, "prop-1", "DEP-1", dummySnapshot(), dummySnapshot(),
            Instant.now().plusSeconds(600), "idem-ctrl-2", "corr-2", null, null
        );

        ReservationAmendmentAssessmentResponse dummyRes = dummyResponse();
        when(assessmentService.assessAmendment(any(), eq(profileId), any())).thenReturn(dummyRes);

        var res = controller.assessAmendment(jwt, cmd, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(bookingClient).get(bookingId);
    }

    @Test
    @DisplayName("Assess amendment: unassigned booking with ASSIGNED scope throws 403 Forbidden")
    void assessAmendment_withAssignedScope_notAssigned_throwsForbidden() {
        when(authorizationClient.require(accountId, FinanceOperationalAuthorization.RESERVATION_AMENDMENT_ASSESS))
            .thenReturn(new Snapshot(true, profileId, 1L, Map.of(
                FinanceOperationalAuthorization.RESERVATION_AMENDMENT_ASSESS, Set.of(Scope.ASSIGNED)
            ), Map.of()));

        UUID otherProfile = UUID.randomUUID();
        when(bookingClient.get(bookingId)).thenReturn(new BookingRecord(bookingId, propertyId, Set.of(otherProfile)));

        var cmd = new AssessReservationAmendmentCommand(
            bookingId, "prop-1", "DEP-1", dummySnapshot(), dummySnapshot(),
            Instant.now().plusSeconds(600), "idem-ctrl-3", "corr-3", null, null
        );

        assertThatThrownBy(() -> controller.assessAmendment(jwt, cmd, null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            });
    }

    @Test
    @DisplayName("Decision: spoofed cross-branch propertyId query param throws 403 Forbidden")
    void getDecision_spoofedPropertyId_throwsForbidden() {
        when(authorizationClient.require(accountId, FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW))
            .thenReturn(new Snapshot(true, profileId, 1L, Map.of(
                FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW, Set.of(Scope.ALL)
            ), Map.of()));

        UUID spoofedPropertyId = UUID.randomUUID();
        assertThatThrownBy(() -> controller.getDecision(
            jwt, bookingId, "prop-1", spoofedPropertyId, null, null, null, null
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(rse.getReason()).contains("Cross-branch property mismatch");
            });
    }

    @Test
    @DisplayName("Field group projection: missing X-Field-Groups returns only baseline RESERVATION_SUMMARY")
    @SuppressWarnings("unchecked")
    void parseFieldGroups_missingHeader_passesOnlySummary() {
        when(authorizationClient.require(accountId, FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW))
            .thenReturn(new Snapshot(true, profileId, 1L, Map.of(
                FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW, Set.of(Scope.ALL)
            ), Map.of()));

        when(assessmentService.getFinalDecision(eq(bookingId), eq("prop-1"), any(), any(), any()))
            .thenReturn(dummyResponse());

        controller.getDecision(jwt, bookingId, "prop-1", null, null, null, null, null);

        ArgumentCaptor<Set<ReservationFieldGroup>> captor = ArgumentCaptor.forClass(Set.class);
        verify(assessmentService).getFinalDecision(eq(bookingId), eq("prop-1"), any(), any(), captor.capture());

        assertThat(captor.getValue()).containsExactly(ReservationFieldGroup.RESERVATION_SUMMARY);
    }

    @Test
    @DisplayName("Field group projection: forged X-Field-Groups without grants is filtered to RESERVATION_SUMMARY")
    @SuppressWarnings("unchecked")
    void parseFieldGroups_forgedHeaderWithoutGrants_isFiltered() {
        // Actor only has basic RESERVATION_DECISION_VIEW with ASSIGNED scope (no financial/audit grant)
        when(authorizationClient.require(accountId, FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW))
            .thenReturn(new Snapshot(true, profileId, 1L, Map.of(
                FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW, Set.of(Scope.ASSIGNED)
            ), Map.of()));
        when(bookingClient.get(bookingId)).thenReturn(new BookingRecord(bookingId, propertyId, Set.of(profileId)));

        when(assessmentService.getFinalDecision(eq(bookingId), eq("prop-1"), any(), any(), any()))
            .thenReturn(dummyResponse());

        // Client attempts to forge FINANCIAL, TRUST_DETAIL, INTERNAL_AUDIT
        controller.getDecision(jwt, bookingId, "prop-1", null, null, null, null, "FINANCIAL,TRUST_DETAIL,INTERNAL_AUDIT");

        ArgumentCaptor<Set<ReservationFieldGroup>> captor = ArgumentCaptor.forClass(Set.class);
        verify(assessmentService).getFinalDecision(eq(bookingId), eq("prop-1"), any(), any(), captor.capture());

        // Forged groups must NOT be passed to service
        assertThat(captor.getValue()).containsExactly(ReservationFieldGroup.RESERVATION_SUMMARY);
    }

    @Test
    @DisplayName("Field group projection: authorized actor requesting FINANCIAL receives FINANCIAL")
    @SuppressWarnings("unchecked")
    void parseFieldGroups_authorizedActorRequestingFinancial_succeeds() {
        when(authorizationClient.require(accountId, FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW))
            .thenReturn(new Snapshot(true, profileId, 1L, Map.of(
                FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW, Set.of(Scope.ALL),
                FinanceOperationalAuthorization.FINANCE_READ, Set.of(Scope.ALL)
            ), Map.of()));

        when(assessmentService.getFinalDecision(eq(bookingId), eq("prop-1"), any(), any(), any()))
            .thenReturn(dummyResponse());

        controller.getDecision(jwt, bookingId, "prop-1", null, null, null, null, "FINANCIAL");

        ArgumentCaptor<Set<ReservationFieldGroup>> captor = ArgumentCaptor.forClass(Set.class);
        verify(assessmentService).getFinalDecision(eq(bookingId), eq("prop-1"), any(), any(), captor.capture());

        assertThat(captor.getValue()).contains(ReservationFieldGroup.RESERVATION_SUMMARY, ReservationFieldGroup.FINANCIAL);
    }

    @Test
    @DisplayName("Field group projection: RESERVATION_SUMMARY redacts financial and trust details")
    void fieldGroupProjection_redactsFinancialWhenNotGranted() {
        var entity = new ReservationAmendmentAssessment(
            UUID.randomUUID(), bookingId, propertyId, customerId, "prop-p1", 1L,
            "1111111111111111111111111111111111111111111111111111111111111111",
            "2222222222222222222222222222222222222222222222222222222222222222",
            UUID.randomUUID(), 1,
            BigDecimal.valueOf(1000000), BigDecimal.valueOf(2000000), BigDecimal.valueOf(1000000),
            BigDecimal.valueOf(1000000), "VND", AmendmentRequiredAction.COLLECTION_REQUIRED,
            AmendmentAssessmentStatus.COLLECTION_REQUIRED, profileId, Instant.now().plusSeconds(600),
            "key-proj", "corr-proj"
        );

        // Projected with ONLY RESERVATION_SUMMARY
        var summaryOnly = ReservationAmendmentAssessmentResponse.fromEntity(
            entity, Set.of(ReservationFieldGroup.RESERVATION_SUMMARY), false, false
        );

        assertThat(summaryOnly.bookingId()).isEqualTo(bookingId);
        assertThat(summaryOnly.status()).isEqualTo(AmendmentAssessmentStatus.COLLECTION_REQUIRED);
        assertThat(summaryOnly.beforeRequiredAmount()).isNull();
        assertThat(summaryOnly.afterRequiredAmount()).isNull();
        assertThat(summaryOnly.verifiedCollectedAmount()).isNull();
        assertThat(summaryOnly.deltaAmount()).isNull();
        assertThat(summaryOnly.beforeSnapshotFingerprint()).isNull();
        assertThat(summaryOnly.afterSnapshotFingerprint()).isNull();

        // Projected with FINANCIAL + TRUST_DETAIL
        var full = ReservationAmendmentAssessmentResponse.fromEntity(
            entity, Set.of(ReservationFieldGroup.FINANCIAL, ReservationFieldGroup.TRUST_DETAIL), false, false
        );

        assertThat(full.beforeRequiredAmount()).isNotNull();
        assertThat(full.afterRequiredAmount()).isNotNull();
        assertThat(full.verifiedCollectedAmount()).isNotNull();
        assertThat(full.deltaAmount()).isNotNull();
        assertThat(full.beforeSnapshotFingerprint()).isNotNull();
        assertThat(full.afterSnapshotFingerprint()).isNotNull();
    }

    @Test
    @DisplayName("Authorization helper: PARTITION scope checks branch mapping")
    void requireReservationAccess_partitionScope_checksBranchMapping() {
        var actor = new FinanceOperationalAuthorization.Actor(
            accountId, profileId, FinanceOperationalAuthorization.RESERVATION_AMENDMENT_ASSESS,
            new Snapshot(true, profileId, 1L, Map.of(
                FinanceOperationalAuthorization.RESERVATION_AMENDMENT_ASSESS, Set.of(Scope.PARTITION)
            ), Map.of(
                FinanceOperationalAuthorization.RESERVATION_AMENDMENT_ASSESS,
                Set.of(new PartitionBinding("BRANCH", UUID.fromString("00000000-0000-0000-0000-000000000099")))
            ))
        );

        when(propertyPartitionClient.resolve(propertyId)).thenReturn(
            Resolution.mapped(UUID.fromString("00000000-0000-0000-0000-000000000099"), 5)
        );

        assertThatCode(() -> authorization.requireReservationAccess(actor, null, propertyId, null))
            .doesNotThrowAnyException();
    }

    private AmendmentProposalSnapshot dummySnapshot() {
        return new AmendmentProposalSnapshot(
            propertyId, UUID.randomUUID(), customerId,
            LocalDate.now().plusDays(5), LocalDate.now().plusDays(7),
            "ROOM", BigDecimal.valueOf(5000000), "VND", 1L, false
        );
    }

    private ReservationAmendmentAssessmentResponse dummyResponse() {
        return new ReservationAmendmentAssessmentResponse(
            UUID.randomUUID(), bookingId, propertyId, "prop-1", 1L, true,
            AmendmentRequiredAction.COLLECTION_REQUIRED, AmendmentAssessmentStatus.COLLECTION_REQUIRED,
            false, 1, "VND", Instant.now().plusSeconds(600), 0L, Instant.now(), false,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
    }
}