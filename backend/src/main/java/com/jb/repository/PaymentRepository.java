package com.jb.repository;

import com.jb.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByGatewayPaymentId(String gatewayPaymentId);
    List<Payment> findByOrderId(UUID orderId);
}
