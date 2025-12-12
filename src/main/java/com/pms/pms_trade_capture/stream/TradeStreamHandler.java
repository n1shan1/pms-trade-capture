package com.pms.pms_trade_capture.stream;


import com.google.protobuf.InvalidProtocolBufferException;
import com.pms.pms_trade_capture.domain.PendingStreamMessage;
import com.pms.pms_trade_capture.service.BatchingIngestService;
import com.pms.trade_capture.proto.TradeEventProto;
import com.rabbitmq.stream.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TradeStreamHandler implements MessageHandler {
    private static final Logger log = LoggerFactory.getLogger(TradeStreamHandler.class);

    private final TradeStreamParser tradeStreamParser;
    private final BatchingIngestService ingestService;

    public TradeStreamHandler(TradeStreamParser tradeStreamParser, BatchingIngestService ingestService) {
        this.tradeStreamParser = tradeStreamParser;
        this.ingestService = ingestService;
    }

    @Override
    public void handle(com.rabbitmq.stream.MessageHandler.Context context, com.rabbitmq.stream.Message message){
        long offset = context.offset();
        byte[] body = message.getBodyAsBinary();

        try {
            // 1. Parse
            TradeEventProto trade = tradeStreamParser.parse(body);

            //2. Validate business Rules
            if (trade.getPortfolioId().isEmpty() || trade.getTradeId().isEmpty()) {
                handleInvalidMessage(body, offset, "Missing required fields: PortfolioID or TradeID");
                return;
            }

            // 3. Route Valid Message
            ingestService.addMessage(new PendingStreamMessage(trade, body, offset));

        } catch (InvalidProtocolBufferException e) {
            // 4. Route Malformed Message (Poison Pill)
            log.warn("Received malformed Protobuf at offset {}", offset);
            handleInvalidMessage(body, offset, "Invalid Protobuf: " + e.getMessage());
        } catch (Exception e) {
            // 5. Route Unexpected Error
            log.error("Unexpected error handling message at offset {}", offset, e);
            handleInvalidMessage(body, offset, "Processing Error: " + e.getMessage());
        }

    }

    private void handleInvalidMessage(byte[] body, long offset, String reason) {
        // We wrap it as an Error message.
        // The BatchingIngestService will persist it to DLQ and THEN commit the offset.
        // This ensures the stream keeps moving even if messages are bad.
        ingestService.addMessage(new PendingStreamMessage(body, offset, reason));
    }

}
