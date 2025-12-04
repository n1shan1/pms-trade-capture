package com.pms.pms_trade_capture.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dlq_entry")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DlqEntry {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt = Instant.now();

    @Lob
    @Column(name = "raw_message", nullable = false, columnDefinition = "text")
    private String rawMessage;

    @Column(name = "error_detail")
    private String errorDetail;

    public DlqEntry(String rawMessage, String errorDetail) {
        this.rawMessage = rawMessage;
        this.errorDetail = errorDetail;
    }

}
