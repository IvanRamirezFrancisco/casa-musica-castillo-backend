package com.security.dto;

import com.security.validation.ValidClabe;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankTransferSettingsRequest {

    @NotBlank(message = "El nombre del banco es requerido")
    @Size(min = 2, max = 100, message = "El nombre del banco debe tener entre 2 y 100 caracteres")
    private String bankName;

    @NotBlank(message = "El titular de la cuenta es requerido")
    @Size(min = 2, max = 150, message = "El titular de la cuenta debe tener entre 2 y 150 caracteres")
    private String accountHolder;

    @NotBlank(message = "La CLABE es requerida")
    @ValidClabe
    private String clabe;

    @Pattern(regexp = "^([0-9]{6,30})?$", message = "El número de cuenta debe tener entre 6 y 30 dígitos numéricos o estar vacío")
    private String accountNumber;

    @Size(max = 500, message = "Las instrucciones de referencia no pueden exceder los 500 caracteres")
    private String referenceInstructions;

    private String additionalInstructions;

    private boolean active = true;

}
