package com.orinan.db.workspacemember;

import com.orinan.db.workspacemember.enums.WorkspaceMemberRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "workspace_members")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkspaceMemberEntity{

    @EmbeddedId
    private WorkspaceMemberId id;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private WorkspaceMemberRole role;

    @CreationTimestamp
    private LocalDateTime registeredAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

}
