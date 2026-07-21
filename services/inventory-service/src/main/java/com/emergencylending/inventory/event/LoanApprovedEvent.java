package com.emergencylending.inventory.event;

import java.time.Instant;

public record LoanApprovedEvent(Long loanRequestId, Long equipmentItemId, Instant approvedAt) {}
