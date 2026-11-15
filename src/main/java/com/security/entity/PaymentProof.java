package com.security.entity;

import com.security.enums.PaymentProofStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 🧾 Entidad PaymentProof - Comprobantes de pago subidos por clientes
 */
@Entity
@Table(name = "payment_proofs", schema = "sales")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProof {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @NotNull(message = "La orden es obligatoria")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    @NotNull(message = "El usuario que sube es obligatorio")
    private User uploadedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "stored_filename", nullable = false, length = 255)
    private String storedFilename;

    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "storage_provider", length = 50)
    private String storageProvider;

    @Column(name = "storage_key", length = 1024)
    private String storageKey;

    @Column(name = "file_url", length = 2048)
    private String fileUrl;

    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Column(name = "storage_folder", length = 500)
    private String storageFolder;

    @Column(name = "reference_number", length = 100)
    private String referenceNumber;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "amount_declared", precision = 10, scale = 2)
    private BigDecimal amountDeclared;

    @Column(name = "transfer_date")
    private LocalDate transferDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentProofStatus status = PaymentProofStatus.PENDING_REVIEW;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    // Explicit getters and setters
    public void setOrder(Order order) { this.order = order; }
    public void setUploadedBy(User uploadedBy) { this.uploadedBy = uploadedBy; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public void setStoredFilename(String storedFilename) { this.storedFilename = storedFilename; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public void setAmountDeclared(BigDecimal amountDeclared) { this.amountDeclared = amountDeclared; }
    public void setTransferDate(LocalDate transferDate) { this.transferDate = transferDate; }
    public void setNotes(String notes) { this.notes = notes; }
    public void setStatus(PaymentProofStatus status) { this.status = status; }
    public void setReviewedBy(User reviewedBy) { this.reviewedBy = reviewedBy; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public void setStorageProvider(String storageProvider) { this.storageProvider = storageProvider; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public void setStorageFolder(String storageFolder) { this.storageFolder = storageFolder; }
    
    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public User getUploadedBy() { return uploadedBy; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public PaymentProofStatus getStatus() { return status; }
    public String getReferenceNumber() { return referenceNumber; }
    public String getBankName() { return bankName; }
    public BigDecimal getAmountDeclared() { return amountDeclared; }
    public LocalDate getTransferDate() { return transferDate; }
    public String getNotes() { return notes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public String getStoredFilename() { return storedFilename; }
    public String getStoragePath() { return storagePath; }
    public String getStorageProvider() { return storageProvider; }
    public String getStorageKey() { return storageKey; }
    public String getFileUrl() { return fileUrl; }
    public String getResourceType() { return resourceType; }
    public String getStorageFolder() { return storageFolder; }
}
