package com.pms.pms_trade_capture.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.pms_trade_capture.dto.TradeEventDto;

import java.util.ArrayList;
import java.util.List;

public class JsonUtil {
    private static final ObjectMapper M = new ObjectMapper();

    public static List<TradeEventDto> fromJsonArrayOrSingle(String txt) throws Exception {
        txt = txt.trim();
        if (txt.startsWith("[")) {
            return M.readValue(txt, new TypeReference<List<TradeEventDto>>() {});
        } else {
            TradeEventDto dto = M.readValue(txt, TradeEventDto.class);
            List<TradeEventDto> l = new ArrayList<>();
            l.add(dto);
            return l;
        }
    }
}

