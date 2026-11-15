package com.security.repository;

import com.security.entity.OrderPickupAuthorization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderPickupAuthorizationRepository extends JpaRepository<OrderPickupAuthorization, Long> {
    List<OrderPickupAuthorization> findByOrderId(Long orderId);
}
