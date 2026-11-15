package com.security.repository;

import com.security.entity.OrderPickupVerificationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderPickupVerificationAttemptRepository extends JpaRepository<OrderPickupVerificationAttempt, Long> {
    List<OrderPickupVerificationAttempt> findByOrderId(Long orderId);
    long countByOrderIdAndSuccessFalse(Long orderId);
}
