package com.vistay.finance.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, UUID> {}
