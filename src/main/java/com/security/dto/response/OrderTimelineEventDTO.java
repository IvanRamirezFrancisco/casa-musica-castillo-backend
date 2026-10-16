package com.security.dto.response;

import com.security.enums.OrderStatus;
import com.security.enums.PaymentStatus;
import com.security.enums.ShippingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTimelineEventDTO {
    private Long id;
    private String eventType;
    private String title;
    private String description;
    
    private OrderStatus orderStatusBefore;
    private OrderStatus orderStatusAfter;
    
    private PaymentStatus paymentStatusBefore;
    private PaymentStatus paymentStatusAfter;
    
    private ShippingStatus shippingStatusBefore;
    private ShippingStatus shippingStatusAfter;
    
    private String actorLabel; // e.g. "Cliente", "Sistema", "Admin", etc.
    private String visibility;
    
    private LocalDateTime createdAt;
}
