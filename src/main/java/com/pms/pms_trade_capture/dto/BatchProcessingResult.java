package com.pms.pms_trade_capture.dto;

import com.pms.pms_trade_capture.exception.PoisonPillException;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of batch processing operation.
 * Contains the successful prefix and any poison pill that was encountered.
 */
public class BatchProcessingResult {
    private final List<Long> successfulIds;
    private final PoisonPillException poisonPill;
    private final boolean systemFailureOccurred;

    private BatchProcessingResult(List<Long> successfulIds, PoisonPillException poisonPill,
            boolean systemFailureOccurred) {
        this.successfulIds = successfulIds != null ? successfulIds : new ArrayList<>();
        this.poisonPill = poisonPill;
        this.systemFailureOccurred = systemFailureOccurred;
    }

    public static BatchProcessingResult success(List<Long> successfulIds) {
        return new BatchProcessingResult(successfulIds, null, false);
    }

    public static BatchProcessingResult withPoisonPill(List<Long> successfulIds, PoisonPillException poisonPill) {
        return new BatchProcessingResult(successfulIds, poisonPill, false);
    }

    public static BatchProcessingResult systemFailure(List<Long> successfulIds) {
        return new BatchProcessingResult(successfulIds, null, true);
    }

    public List<Long> getSuccessfulIds() {
        return successfulIds;
    }

    public PoisonPillException getPoisonPill() {
        return poisonPill;
    }

    public boolean hasPoisonPill() {
        return poisonPill != null;
    }

    public boolean hasSystemFailure() {
        return systemFailureOccurred;
    }

    public boolean isFullSuccess() {
        return !hasPoisonPill() && !hasSystemFailure();
    }
}
