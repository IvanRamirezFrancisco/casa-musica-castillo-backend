package com.security.dto.response;

import com.security.enums.OrderStatus;
import com.security.enums.PaymentStatus;
import com.security.enums.ShippingStatus;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OrderTransitionsDTO {
    private OrderStatus currentOrderStatus;
    private PaymentStatus currentPaymentStatus;
    private ShippingStatus currentShippingStatus;
    
    private List<OrderStatus> allowedOrderStatuses;
    private List<ShippingStatus> allowedShippingStatuses;
    
    private boolean canUpdateOrderStatus;
    private boolean canUpdateShippingStatus;
    private boolean canUpdateTrackingNumber;
    
    private boolean canCancelOrder;
    private boolean requiresCancelReason;
    
    private String orderStatusHelpMessage;
    private String shippingStatusHelpMessage;
    private String cancelOrderHelpMessage;
}
