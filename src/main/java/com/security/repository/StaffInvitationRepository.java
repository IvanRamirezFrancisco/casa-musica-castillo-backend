package com.security.repository;

import com.security.entity.StaffInvitation;
import com.security.enums.InvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StaffInvitationRepository extends JpaRepository<StaffInvitation, Long> {

    Optional<StaffInvitation> findByTokenHash(String tokenHash);

    Optional<StaffInvitation> findByEmailAndStatus(String email, InvitationStatus status);

    List<StaffInvitation> findByStatusOrderByCreatedAtDesc(InvitationStatus status);

    boolean existsByEmailAndStatus(String email, InvitationStatus status);

    List<StaffInvitation> findAllByOrderByCreatedAtDesc();
}
