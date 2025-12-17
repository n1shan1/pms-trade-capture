package com.pms.pms_trade_capture.controller;

import java.util.HexFormat;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pms.pms_trade_capture.domain.PendingStreamMessage;
import com.pms.pms_trade_capture.service.BatchingIngestService;

@RestController
@RequestMapping("/admin/replay")
public class ReplayController {

    private final BatchingIngestService ingestService;

    public ReplayController(BatchingIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/hex")
    public ResponseEntity<String> replayHexTrade(@RequestBody String hexData) {
        try {
            // 1. Convert Hex -> Bytes
            byte[] rawBytes = HexFormat.of().parseHex(hexData);

            // 2. Wrap in a message (Use dummy offset -1, logic handles it)
            // We pass 'null' context because we don't need to ack RabbitMQ for this manual
            // replay
            PendingStreamMessage msg = new PendingStreamMessage(null, rawBytes, -1, null);

            // 3. Inject directly into the Buffer
            ingestService.addMessage(msg);

            return ResponseEntity.ok("Replay injected into buffer.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid Hex");
        }
    }
}
