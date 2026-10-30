package com.hotdeal.parser;

import static com.hotdeal.parser.UnitPriceParser.Type.PER_100G;
import static com.hotdeal.parser.UnitPriceParser.Type.PER_100ML;
import static com.hotdeal.parser.UnitPriceParser.Type.PER_ITEM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hotdeal.parser.UnitPriceParser.Result;
import com.hotdeal.parser.UnitPriceParser.Type;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UnitPriceParserTest {

    static Stream<Arguments> parsedCases() {
        return Stream.of(
                // 개수 단가
                Arguments.of("물티슈 100매 x 10팩", PER_ITEM, 1000.0),
                Arguments.of("화장지 30롤 3팩", PER_ITEM, 90.0),
                Arguments.of("계란 30구 2판", PER_ITEM, 60.0),
                Arguments.of("1,000매 x 2팩", PER_ITEM, 2000.0),
                Arguments.of("커피믹스 100개입 x 3", PER_ITEM, 300.0),
                Arguments.of("1박스(20개)", PER_ITEM, 20.0),
                Arguments.of("2박스(20개입)", PER_ITEM, 40.0),
                Arguments.of("10개입 x 2박스 (20개)", PER_ITEM, 20.0),
                // 무게 단가
                Arguments.of("닭가슴살 100g 20팩 + 2팩 증정", PER_100G, 2200.0),
                Arguments.of("신라면 120g x 5개입 x 8봉", PER_100G, 4800.0),
                Arguments.of("만두 1.05kg", PER_100G, 1050.0),
                Arguments.of("참치 150g*10캔 + 2캔 증정", PER_100G, 1800.0),
                Arguments.of("견과 25g x 30봉 (1박스 30개)", PER_100G, 750.0),
                Arguments.of("닭가슴살 100gx20팩", PER_100G, 2000.0),
                // 부피 단가
                Arguments.of("생수 2L x 6병", PER_100ML, 12000.0),
                Arguments.of("우유 1L x 10개", PER_100ML, 10000.0),
                Arguments.of("콜라 500ml × 24개", PER_100ML, 12000.0),
                Arguments.of("세제 3L 1+1", PER_100ML, 6000.0),
                Arguments.of("샴푸 750ml 2개 1+1", PER_100ML, 3000.0),
                Arguments.of("주방세제 500ml 3개입 1+1 행사", PER_100ML, 3000.0),
                // 총량 표기
                Arguments.of("생수 500ml x 20병 (총 10L)", PER_100ML, 10000.0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsedCases")
    void 수량과_용량을_계산한다(String title, Type expectedType, double expectedTotal) {
        Result r = UnitPriceParser.parse(title);
        assertTrue(r.parsed(), () -> "파싱 실패: " + title + " / " + r.reason());
        assertEquals(expectedType, r.type());
        assertEquals(expectedTotal, r.totalQuantity(), 1e-6);
    }

    static Stream<String> failedCases() {
        return Stream.of(
                "무선 이어폰 특가",
                "보조배터리 20000mAh",
                "RTX 4090 그래픽카드",
                "삼양라면 5개입 600g",          // 용량이 수량 뒤: 총량인지 개당인지 모호
                "닭가슴살 100g x 20팩 (2kg)",   // 용량 표기가 서로 다름
                "500g + 500g 세트",             // 용량 합산
                "물 500ml 500g",                // g 와 ml 혼재
                "비타민 3개월분",                // 기간은 수량이 아님
                "",
                "   ");
    }

    @ParameterizedTest(name = "계산 불가: {0}")
    @MethodSource("failedCases")
    void 모호하면_계산불가로_처리한다(String title) {
        Result r = UnitPriceParser.parse(title);
        assertFalse(r.parsed(), () -> "계산되면 안 됨: " + title + " → " + r.totalQuantity());
    }

    @Test
    void null_은_계산불가() {
        assertFalse(UnitPriceParser.parse(null).parsed());
    }

    @Test
    void 단가_계산() {
        // 19,900원 / 2,200g * 100 = 904.5원 (100g당)
        Result r = UnitPriceParser.parse("닭가슴살 100g 20팩 + 2팩 증정");
        assertEquals(904.5, r.unitPrice(19900), 0.1);

        // 14,900원 / 1,000매 = 14.9원 (매당)
        Result paper = UnitPriceParser.parse("물티슈 100매 x 10팩");
        assertEquals(14.9, paper.unitPrice(14900), 1e-6);
    }
}
