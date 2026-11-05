package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.inductiveautomation.ignition.common.gson.JsonParser;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayCamelServicesTest {
    @Test
    void convertsScriptJsonWithoutLosingIntegerPrecision() {
        assertEquals(Map.of("values", List.of(9007199254740993L, true, "ok")),
            GatewayCamelServices.toJava(JsonParser.parseString("""
                {"values":[9007199254740993,true,"ok"]}
                """)));
        assertEquals(new BigInteger("9223372036854775808"),
            GatewayCamelServices.toJava(JsonParser.parseString("9223372036854775808")));
        assertEquals(new BigDecimal("1.25"),
            GatewayCamelServices.toJava(JsonParser.parseString("1.25")));
        assertNull(GatewayCamelServices.toJava(JsonParser.parseString("null")));
    }
}
