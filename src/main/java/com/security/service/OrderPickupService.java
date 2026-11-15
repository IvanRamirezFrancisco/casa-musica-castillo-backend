package com.security.service;

import com.security.entity.Order;
import com.security.entity.OrderPickupVerificationAttempt;
import com.security.enums.OrderStatus;
import com.security.enums.PaymentStatus;
import com.security.enums.PickupStatus;
import com.security.enums.ShippingStatus;
import com.security.repository.OrderPickupVerificationAttemptRepository;
import com.security.repository.OrderRepository;
import com.security.repository.OrderPickupAuthorizationRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class OrderPickupService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderPickupVerificationAttemptRepository attemptRepository;

    @Autowired
    private OrderPickupAuthorizationRepository authRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OrderTimelineEventService timelineEventService;

    // Sin default: debe proveerse vía PICKUP_CODE_ENCRYPTION_KEY (pickup.code.encryption.key).
    @Value("${pickup.code.encryption.key}")
    private String encryptionKey;

    /**
     * Valida al arrancar que la clave AES exista y tenga un tamaño válido
     * (16, 24 o 32 bytes), para fallar rápido con un mensaje claro.
     */
    @PostConstruct
    void validateEncryptionKey() {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException(
                    "PICKUP_CODE_ENCRYPTION_KEY no está configurada. Define una clave AES de 16, 24 o 32 caracteres ASCII.");
        }
        int length = encryptionKey.getBytes(StandardCharsets.UTF_8).length;
        if (length != 16 && length != 24 && length != 32) {
            throw new IllegalStateException(
                    "PICKUP_CODE_ENCRYPTION_KEY tiene " + length
                            + " bytes; AES requiere exactamente 16, 24 o 32 bytes (se recomienda 32).");
        }
    }

    private static final String ALGORITHM = "AES";
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final String CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // Removed ambiguous chars like 0, O, 1, I

    public void generatePickupCode(Order order) {
        if (!"PICKUP_STORE".equals(order.getDeliveryType())) {
            return;
        }
        
        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            return;
        }

        // Only generate if we don't have one or if it needs to be regenerated
        if (order.getPickupCodeHash() != null) {
            return;
        }

        String plainCode = "CMC-" + generateRandomString(6);
        String encryptedCode = encrypt(plainCode);
        String codeHash = passwordEncoder.encode(plainCode);
        String last4 = plainCode.substring(plainCode.length() - 4);

        order.setPickupCodeEncrypted(encryptedCode);
        order.setPickupCodeHash(codeHash);
        order.setPickupCodeLast4(last4);
        order.setPickupStatus(PickupStatus.PAID_WAITING_PREPARATION);

        orderRepository.save(order);

        try {
            timelineEventService.recordEvent(
                order, "PICKUP_CODE_GENERATED", "Recolección preparada",
                "Se generó el código de recolección. Lo verás cuando tu pedido esté listo para recoger.",
                null, order.getStatus(), null, order.getPaymentStatus(), null, order.getShippingStatus(),
                order.getUser(), "SYSTEM", "ORDER_PICKUP_SERVICE", "BOTH", null
            );
        } catch (Exception e) {
            // Log and ignore
        }
    }

    public void markReadyForPickup(Long orderId, Long adminId, com.security.entity.User adminUser) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new RuntimeException("Order not found"));
        
        if (!"PICKUP_STORE".equals(order.getDeliveryType())) {
            throw new RuntimeException("This order is not for pickup");
        }
        
        if (order.getPickupStatus() != PickupStatus.PAID_WAITING_PREPARATION) {
            throw new RuntimeException("Order must be paid and waiting for preparation");
        }
        
        order.setPickupStatus(PickupStatus.READY_FOR_PICKUP);
        order.setPickupReadyAt(LocalDateTime.now());
        
        orderRepository.save(order);
        
        try {
            timelineEventService.recordEvent(
                order, "PICKUP_READY", "Pedido listo para recoger",
                "Tu pedido ya puede recogerse en tienda con el código de recolección y una identificación oficial.",
                null, order.getStatus(), null, order.getPaymentStatus(), null, order.getShippingStatus(),
                adminUser, "ADMIN", "ORDER_PICKUP_SERVICE", "BOTH", null
            );
        } catch (Exception e) {
            // Log and ignore
        }
    }

    public void verifyAndReleasePickup(Long orderId, String plainCode, Long pickupAuthorizationId, String notes, Long adminId, com.security.entity.User adminUser, String ipAddress, String userAgent) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new RuntimeException("Order not found"));
        
        if (!"PICKUP_STORE".equals(order.getDeliveryType())) {
            throw new RuntimeException("This order is not for pickup");
        }
        
        if (order.getPickupStatus() != PickupStatus.READY_FOR_PICKUP) {
            throw new RuntimeException("Order must be ready for pickup");
        }

        com.security.entity.OrderPickupAuthorization auth = authRepository.findById(pickupAuthorizationId)
            .orElseThrow(() -> new RuntimeException("Autorización no encontrada"));

        if (!auth.getOrder().getId().equals(order.getId())) {
            throw new RuntimeException("La persona autorizada no pertenece a esta orden");
        }

        long failedAttempts = attemptRepository.countByOrderIdAndSuccessFalse(orderId);
        if (failedAttempts >= 5) {
            throw new RuntimeException("Demasiados intentos fallidos. Debe regenerar el código.");
        }

        boolean isCodeValid = passwordEncoder.matches(plainCode, order.getPickupCodeHash());
        
        OrderPickupVerificationAttempt attempt = new OrderPickupVerificationAttempt();
        attempt.setOrder(order);
        attempt.setAdminId(adminId);
        attempt.setSuccess(isCodeValid);
        attempt.setPickupAuthorizationId(pickupAuthorizationId);
        attempt.setIpAddress(ipAddress);
        attempt.setUserAgent(userAgent);
        
        if (!isCodeValid) {
            attempt.setFailureReason("Código de recolección incorrecto");
            attemptRepository.save(attempt);
            
            try {
                timelineEventService.recordEvent(
                    order, "PICKUP_VERIFICATION_FAILED", "Intento de recolección fallido",
                    "Código incorrecto provisto para la persona autorizada " + auth.getFullName() + ".",
                    null, order.getStatus(), null, order.getPaymentStatus(), null, order.getShippingStatus(),
                    adminUser, "ADMIN", "ORDER_PICKUP_SERVICE", "ADMIN_ONLY", null
                );
            } catch (Exception e) {}
            
            throw new RuntimeException("El código de recolección es incorrecto.");
        }
        
        attemptRepository.save(attempt);
        
        // Success flow
        order.setPickupStatus(PickupStatus.PICKED_UP);
        order.setPickedUpAt(LocalDateTime.now());
        order.setPickedUpByName(auth.getFullName());
        order.setPickedUpByAuthorizationId(pickupAuthorizationId);
        order.setVerifiedByAdminId(adminId);
        order.setStatus(OrderStatus.COMPLETED);
        order.setShippingStatus(ShippingStatus.DELIVERED); // Internal compatibility
        
        if (notes != null && !notes.isBlank()) {
            String currentNotes = order.getNotes() == null ? "" : order.getNotes() + "\n";
            order.setNotes(currentNotes + "[Entrega en tienda]: " + notes);
        }
        
        orderRepository.save(order);
        
        try {
            timelineEventService.recordEvent(
                order, "ORDER_PICKED_UP", "Pedido recolectado",
                "El pedido fue recolectado en tienda.",
                null, order.getStatus(), null, order.getPaymentStatus(), null, order.getShippingStatus(),
                adminUser, "ADMIN", "ORDER_PICKUP_SERVICE", "BOTH", null
            );
        } catch (Exception e) {
            // Log and ignore
        }
    }

    public String getPlainCodeForCustomer(Order order) {
        if (!"PICKUP_STORE".equals(order.getDeliveryType())) return null;
        if (order.getPaymentStatus() != PaymentStatus.PAID) return null;
        if (order.getPickupStatus() == PickupStatus.PICKED_UP) return null;
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.COMPLETED) return null;
        
        return decryptPickupCode(order.getPickupCodeEncrypted());
    }

    public String decryptPickupCode(String encryptedCode) {
        if (encryptedCode == null) return null;
        try {
            SecretKeySpec key = new SecretKeySpec(encryptionKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedCode));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null; // Return null if decryption fails (e.g., bad key)
        }
    }

    private String encrypt(String plainText) {
        try {
            SecretKeySpec key = new SecretKeySpec(encryptionKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Error encrypting pickup code", e);
        }
    }

    private String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARACTERS.charAt(secureRandom.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
}
