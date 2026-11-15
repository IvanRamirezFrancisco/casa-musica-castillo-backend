package com.security.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PaymentReconciliationResponse {
    private boolean dryRun;
    private long evaluatedOrders;
    private long activePaymentsFound;
    private long wouldCancelPayments;
    private long cancelledPayments;
    private String message;
    private List<PaymentReconciliationItemResponse> items;
}
