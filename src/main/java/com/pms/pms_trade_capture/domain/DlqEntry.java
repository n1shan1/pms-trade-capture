package com.pms.pms_trade_capture.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dlq_entry")
@Data
@NoArgsConstructor
public class DlqEntry {
    // Note: allocationSize = 50 matches hibernate.jdbc.batch_size.
    // This is critical for batch inserts to work efficiently without fetching a
    // sequence value for every single row.
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "dlq_seq")
    @SequenceGenerator(name = "dlq_seq", sequenceName = "dlq_entry_seq", allocationSize = 1)
    private Long id;

    @Column(name = "failed_at", nullable = false)
    private LocalDateTime failedAt = LocalDateTime.now();

    // FIX: Add this annotation to force strict binary storage
    @Column(name = "raw_message", nullable = false)
    @JdbcTypeCode(SqlTypes.VARBINARY)
    private byte[] rawMessage;

    @Column(name = "error_detail", nullable = false, length = 4096)
    private String errorDetail;

    public DlqEntry(byte[] rawMessage, String errorDetail) {
        this.rawMessage = rawMessage;
        this.errorDetail = errorDetail;
    }
}
