package com.pms.pms_trade_capture.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pms.pms_trade_capture.domain.DlqEntry;

@Repository
public interface DlqRepository extends JpaRepository<DlqEntry, UUID> {
}
