package com.security.dto.alexa;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlexaCategoryDTO {
    private Long id;
    private String name;
    private String description;
}
