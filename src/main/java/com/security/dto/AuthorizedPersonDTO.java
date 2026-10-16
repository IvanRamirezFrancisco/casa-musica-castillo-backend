package com.security.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizedPersonDTO {
    private Long id;
    private String fullName;
    private String phone;
    private String relationship;
    private boolean isPrimary;
}
