package com.emergencylending.loan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request DTO for creating a new loan request.
 *
 * <p>Status and timestamps are intentionally excluded — they are managed
 * internally by the service lifecycle.
 */
@Data
public class LoanRequestCreateDto {

    @NotNull(message = "Equipment item ID must not be null")
    private Long equipmentItemId;

    @NotBlank(message = "Borrower name must not be blank")
    private String borrowerName;

    @NotBlank(message = "Borrower contact must not be blank")
    private String borrowerContact;
}
