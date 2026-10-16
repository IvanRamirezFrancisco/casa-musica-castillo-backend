package com.security.repository;

import com.security.entity.OrderTimelineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderTimelineEventRepository extends JpaRepository<OrderTimelineEvent, Long> {
    
    List<OrderTimelineEvent> findByOrderIdOrderByCreatedAtDesc(Long orderId);
    
    List<OrderTimelineEvent> findByOrderIdAndVisibilityInOrderByCreatedAtDesc(Long orderId, List<String> visibilities);
}
