package com.security.repository;

import com.security.entity.PaymentProof;
import com.security.enums.PaymentProofStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentProofRepository extends JpaRepository<PaymentProof, Long> {

    Optional<PaymentProof> findTopByOrderIdOrderByCreatedAtDesc(Long orderId);

    List<PaymentProof> findByOrderId(Long orderId);

    boolean existsByOrderIdAndStatus(Long orderId, PaymentProofStatus status);

    Optional<PaymentProof> findByOrderIdAndStatus(Long orderId, PaymentProofStatus status);

    List<PaymentProof> findByStatus(PaymentProofStatus status);
}
