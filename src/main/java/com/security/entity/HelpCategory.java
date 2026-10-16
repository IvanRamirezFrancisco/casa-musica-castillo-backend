package com.security.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "help_categories", schema = "ops")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HelpCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "El nombre de la categoría es requerido")
    @Size(max = 150, message = "El nombre no puede exceder los 150 caracteres")
    @Column(nullable = false, length = 150)
    private String name;

    @NotBlank(message = "El slug es requerido")
    @Size(max = 150, message = "El slug no puede exceder los 150 caracteres")
    @Column(nullable = false, unique = true, length = 150)
    private String slug;

    @Size(max = 500, message = "La descripción no puede exceder los 500 caracteres")
    @Column(length = 500)
    private String description;

    @Size(max = 50, message = "El ícono no puede exceder los 50 caracteres")
    @Column(name = "icon_key", length = 50)
    private String iconKey;

    @Min(value = 0, message = "El orden no puede ser negativo")
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
