package com.vistay.finance.adapter.in.grpc;

import com.google.protobuf.Timestamp;
import com.vistay.contracts.common.v1.Money;
import com.vistay.contracts.finance.v1.FinancePaymentInstructionServiceGrpc;
import com.vistay.contracts.finance.v1.GetPaymentInstructionRequest;
import com.vistay.contracts.finance.v1.GetPaymentInstructionResponse;
import com.vistay.finance.domain.PaymentRequest;
import com.vistay.finance.domain.PaymentRequestRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinancePaymentInstructionGrpcService extends FinancePaymentInstructionServiceGrpc.FinancePaymentInstructionServiceImplBase {
    private static final Set<String> PAYABLE_STATUSES = Set.of("PENDING", "PROCESSING", "PARTIALLY_PAID");
    private final PaymentRequestRepository requests;

    public FinancePaymentInstructionGrpcService(PaymentRequestRepository requests) {
        this.requests = requests;
    }

    @Override
    @Transactional(readOnly = true)
    public void getPaymentInstruction(GetPaymentInstructionRequest request,
                                      StreamObserver<GetPaymentInstructionResponse> observer) {
        try {
            var id = UUID.fromString(request.getFinancePaymentRequestId());
            var paymentRequest = requests.findById(id)
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("Finance payment request not found").asRuntimeException());
            validate(paymentRequest);
            observer.onNext(toResponse(paymentRequest));
            observer.onCompleted();
        } catch (IllegalArgumentException error) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("Invalid finance payment request id").asRuntimeException());
        } catch (io.grpc.StatusRuntimeException error) {
            observer.onError(error);
        } catch (ArithmeticException error) {
            observer.onError(Status.FAILED_PRECONDITION
                .withDescription("Payment amount has unsupported fractional minor units").asRuntimeException());
        }
    }

    private static void validate(PaymentRequest request) {
        if (!PAYABLE_STATUSES.contains(request.getStatus())) {
            throw Status.FAILED_PRECONDITION.withDescription("Finance payment request is not payable").asRuntimeException();
        }
        if (request.getExpiresAt() == null || !request.getExpiresAt().isAfter(Instant.now())) {
            throw Status.FAILED_PRECONDITION.withDescription("Finance payment request is expired or has no expiry").asRuntimeException();
        }
    }

    private static GetPaymentInstructionResponse toResponse(PaymentRequest request) {
        var expiresAt = request.getExpiresAt();
        var response = GetPaymentInstructionResponse.newBuilder()
            .setFinancePaymentRequestId(request.getId().toString())
            .setAmount(Money.newBuilder()
                .setMinorUnits(request.getAmount().longValueExact())
                .setCurrency(request.getCurrency()))
            .setPurpose(request.getPurpose())
            .setPayerReference(request.getRequestCode())
            .setExpiresAt(Timestamp.newBuilder()
                .setSeconds(expiresAt.getEpochSecond())
                .setNanos(expiresAt.getNano()))
            .setVersion(request.getVersion());
        if (request.getBookingId() != null) response.setBookingId(request.getBookingId().toString());
        if ("CUSTOMER".equals(request.getPayerType()) && request.getPayerId() != null) {
            response.setCustomerId(request.getPayerId().toString());
        }
        return response.build();
    }
}
