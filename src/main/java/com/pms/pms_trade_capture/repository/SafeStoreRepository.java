package com.pms.pms_trade_capture.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pms.pms_trade_capture.domain.SafeStoreTrade;

@Repository
public interface SafeStoreRepository extends JpaRepository<SafeStoreTrade, UUID> {
}
