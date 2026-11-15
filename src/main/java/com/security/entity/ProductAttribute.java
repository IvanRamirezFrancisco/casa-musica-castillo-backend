package com.security.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Objects;

@Entity
@Table(name = "product_attributes", schema = "catalog", indexes = {
        @Index(name = "idx_product_attrs_name", columnList = "attribute_name")
})
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "product")
public class ProductAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @NotBlank(message = "El nombre del atributo es obligatorio")
    @Size(max = 100, message = "El nombre del atributo no puede exceder 100 caracteres")
    @Column(name = "attribute_name", nullable = false, length = 100)
    private String attributeName;

    @NotBlank(message = "El valor del atributo es obligatorio")
    @Size(max = 255, message = "El valor del atributo no puede exceder 255 caracteres")
    @Column(name = "attribute_value", nullable = false, length = 255)
    private String attributeValue;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    public ProductAttribute(String attributeName, String attributeValue, Integer displayOrder) {
        this.attributeName = attributeName;
        this.attributeValue = attributeValue;
        this.displayOrder = displayOrder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ProductAttribute other))
            return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
