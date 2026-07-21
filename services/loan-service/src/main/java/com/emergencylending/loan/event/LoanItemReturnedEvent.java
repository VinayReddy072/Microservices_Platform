package com.emergencylending.loan.event;

import java.time.Instant;

public record LoanItemReturnedEvent(Long loanRequestId, Long equipmentItemId, Instant returnedAt) {}
