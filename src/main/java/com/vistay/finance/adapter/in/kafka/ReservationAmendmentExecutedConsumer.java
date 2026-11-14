package com.vistay.finance.adapter.in.kafka;

import com.vistay.finance.application.dto.ReservationAmendmentExecutedCommand;
import com.vistay.finance.application.service.ReservationAmendmentAssessmentService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReservationAmendmentExecutedConsumer {

    private final ReservationAmendmentAssessmentService assessmentService;
    private final ObjectMapper objectMapper;

    public ReservationAmendmentExecutedConsumer(
        ReservationAmendmentAssessmentService assessmentService,
        ObjectMapper objectMapper
    ) {
        this.assessmentService = assessmentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${vistay.topics.booking-amendment-executed:vistay.booking.amendment-executed.v1}")
    public void consume(String payload) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid amendment-executed event payload", ex);
        }

        JsonNode data = root.hasNonNull("data") ? root.get("data") : root;

        UUID eventId = root.hasNonNull("eventId")
            ? UUID.fromString(root.get("eventId").asText())
            : (data.hasNonNull("eventId") ? UUID.fromString(data.get("eventId").asText()) : UUID.randomUUID());

        UUID bookingId = UUID.fromString(data.required("bookingId").asText());
        String proposalId = data.required("proposalId").asText();
        String afterFingerprint = data.required("afterSnapshotFingerprint").asText();

        UUID customerId = data.hasNonNull("customerId")
            ? UUID.fromString(data.get("customerId").asText())
            : null;

        LocalDate dueDate = data.hasNonNull("dueDate")
            ? LocalDate.parse(data.get("dueDate").asText())
            : null;

        String correlationId = root.hasNonNull("correlationId")
            ? root.get("correlationId").asText()
            : (data.hasNonNull("correlationId") ? data.get("correlationId").asText() : null);

        UUID actorAdminUserId = data.hasNonNull("actorAdminUserId")
            ? UUID.fromString(data.get("actorAdminUserId").asText())
            : null;

        ReservationAmendmentExecutedCommand command = new ReservationAmendmentExecutedCommand(
            eventId,
            bookingId,
            proposalId,
            afterFingerprint,
            customerId,
            dueDate,
            correlationId,
            actorAdminUserId
        );

        assessmentService.processAmendmentExecutedEvent(command);
    }
}
