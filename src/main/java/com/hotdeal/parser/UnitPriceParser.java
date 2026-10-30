package com.hotdeal.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 핫딜 제목에서 수량/용량을 뽑아 "개당 / 100g당 / 100ml당" 단가를 계산하는 규칙 기반 파서.
 *
 * 원칙: 잘못된 단가보다 "계산 불가"가 낫다. 조금이라도 모호하면 parsed=false 로 돌려준다.
 * (모호한 건 나중에 LLM 보조 단계로 넘길 후보다.)
 *
 * 지원하는 패턴 예
 *  - "물티슈 100매 x 10팩"              → 1000매 (개당)
 *  - "닭가슴살 100g 20팩 + 2팩 증정"     → 2200g (100g당)
 *  - "신라면 120g x 5개입 x 8봉"         → 4800g
 *  - "세제 3L 1+1"                      → 6000ml
 *  - "생수 500ml x 20병 (총 10L)"        → "총" 표기가 있으면 그 값을 총량으로 사용
 *
 * 알려진 한계
 *  - "5G 스마트폰"처럼 식품/생활용품이 아닌 제목은 오탐할 수 있다 (카테고리 필터는 이후 단계).
 *  - "5개입 600g"처럼 용량이 수량 뒤에 오면 총량인지 개당인지 알 수 없어 계산 불가로 처리한다.
 */
public final class UnitPriceParser {

    public enum Type {
        PER_ITEM("per_item", 1),
        PER_100G("per_100g", 100),
        PER_100ML("per_100ml", 100);

        public final String json;
        /** 단가 = 가격 / 총량 * basis */
        public final int basis;

        Type(String json, int basis) {
            this.json = json;
            this.basis = basis;
        }
    }

    public record Result(boolean parsed, Type type, double totalQuantity, String reason) {
        public static Result failed(String reason) {
            return new Result(false, null, 0, reason);
        }

        public static Result ok(Type type, double total) {
            return new Result(true, type, total, "ok");
        }

        /** 단가 (개당 원, 또는 100g/100ml 당 원). parsed=false 이면 사용 불가. */
        public double unitPrice(int price) {
            if (!parsed) {
                throw new IllegalStateException("단가를 계산할 수 없는 결과입니다: " + reason);
            }
            return price / totalQuantity * type.basis;
        }
    }

    // ---- 단위 정의 -------------------------------------------------------
    /** 용량 단위 → (종류, 기준단위(g/ml)로의 환산 배수). 긴 이름이 앞에 와야 한다. */
    private static final String MEASURE_UNITS = "kg|ml|g|l|리터";
    private static final String COUNT_UNITS =
            "개입|봉지|포기|세트|박스|ea|개|매|팩|봉|입|병|캔|롤|포|장|통|판|알|정|갑|곽|구|조각|켤레|족";
    private static final String ALL_UNITS = MEASURE_UNITS + "|" + COUNT_UNITS;

    private enum Kind { G, ML }

    private record MeasureUnit(Kind kind, double factor) {}

    private static final Map<String, MeasureUnit> MEASURE = Map.of(
            "kg", new MeasureUnit(Kind.G, 1000),
            "g", new MeasureUnit(Kind.G, 1),
            "ml", new MeasureUnit(Kind.ML, 1),
            "l", new MeasureUnit(Kind.ML, 1000),
            "리터", new MeasureUnit(Kind.ML, 1000));

    // ---- 정규식 ---------------------------------------------------------
    /** 숫자 + (선택)단위. 소수점/자리수 역추적을 막기 위해 소유형 수량자(++)를 쓴다. */
    private static final Pattern TOKEN = Pattern.compile(
            "(?<![\\d.])(\\d++(?:\\.\\d++)?)\\s*(" + ALL_UNITS + ")?(?![a-z])");

    /** "총 10L", "총 40개" 처럼 총량이 명시된 경우 */
    private static final Pattern TOTAL = Pattern.compile(
            "총\\s*(\\d++(?:\\.\\d++)?)\\s*(" + ALL_UNITS + ")(?![a-z])");

    /** "1+1", "2+1" 같은 묶음 행사 (양쪽 다 단위가 없는 경우만) */
    private static final Pattern BUNDLE = Pattern.compile(
            "(?<![\\d.+])(\\d{1,2})\\s*\\+\\s*(\\d{1,2})(?![\\d.]|\\s*(?:" + ALL_UNITS + ")|[a-z])");

    private static final double MAX_TOTAL = 1_000_000;

    private UnitPriceParser() {}

    // ---- 메인 -----------------------------------------------------------
    public static Result parse(String title) {
        if (title == null || title.isBlank()) {
            return Result.failed("빈 제목");
        }
        String s = normalize(title);

        // 1) "총 N단위" 가 있으면 그 값을 그대로 총량으로 사용
        Matcher tm = TOTAL.matcher(s);
        if (tm.find()) {
            double v = Double.parseDouble(tm.group(1));
            String unit = tm.group(2);
            MeasureUnit mu = MEASURE.get(unit);
            if (mu != null) {
                return finish(mu.kind() == Kind.G ? Type.PER_100G : Type.PER_100ML, v * mu.factor());
            }
            return finish(Type.PER_ITEM, v);
        }

        // 2) 1+1 같은 묶음 행사는 배수로 처리하고 문자열에서 제거
        int bundle = 1;
        Matcher bm = BUNDLE.matcher(s);
        if (bm.find()) {
            bundle = Integer.parseInt(bm.group(1)) + Integer.parseInt(bm.group(2));
            s = s.substring(0, bm.start()) + " " + s.substring(bm.end());
        }

        // 3) 토큰 수집
        List<Measure> measures = new ArrayList<>();
        List<Count> counts = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            double value = Double.parseDouble(m.group(1));
            String unit = m.group(2);
            int start = m.start();
            boolean plus = prevNonSpace(s, start) == '+';

            if (unit != null && MEASURE.containsKey(unit)) {
                MeasureUnit mu = MEASURE.get(unit);
                measures.add(new Measure(mu.kind(), value * mu.factor(), start, plus));
            } else if (unit != null) {
                addCount(counts, new Count(value, unit, start), plus);
            } else if (isAfterX(s, start) && !plus) {
                // "100g x 20" 처럼 곱하기 뒤에 단위 없이 오는 수량
                addCount(counts, new Count(value, null, start), false);
            }
        }

        // 4) 중복 표기("10개입 x 2박스 (20개)") 제거: 나머지의 곱과 같은 값이면 총합 표기로 보고 버림
        dropRedundantTotal(counts);

        if (counts.stream().anyMatch(c -> c.value <= 0)) {
            return Result.failed("수량이 0 이하");
        }
        double countProduct = 1;
        for (Count c : counts) {
            countProduct *= c.value;
        }

        // 5) 용량이 없는 경우 → 개수 단가
        if (measures.isEmpty()) {
            if (counts.isEmpty()) {
                return Result.failed("수량/용량 표기를 찾지 못함");
            }
            return finish(Type.PER_ITEM, countProduct * bundle);
        }

        // 6) 용량이 있는 경우 → 모호한 조합은 포기
        if (measures.stream().anyMatch(x -> x.plus)) {
            return Result.failed("용량 합산(+) 표기는 모호함");
        }
        Kind kind = measures.get(0).kind;
        double amount = measures.get(0).amount;
        for (Measure x : measures) {
            if (x.kind != kind) {
                return Result.failed("g 와 ml 이 섞여 있음");
            }
            if (Math.abs(x.amount - amount) > 1e-9) {
                return Result.failed("서로 다른 용량 표기가 여러 개");
            }
        }
        if (!counts.isEmpty()) {
            int firstCountStart = counts.stream().mapToInt(c -> c.start).min().getAsInt();
            if (measures.get(0).start > firstCountStart) {
                return Result.failed("용량이 수량 뒤에 있어 총량/개당 구분 불가");
            }
        }
        double total = amount * countProduct * bundle;
        return finish(kind == Kind.G ? Type.PER_100G : Type.PER_100ML, total);
    }

    // ---- 내부 도우미 ----------------------------------------------------
    private record Measure(Kind kind, double amount, int start, boolean plus) {}

    private static final class Count {
        double value;
        final String unit;
        final int start;

        Count(double value, String unit, int start) {
            this.value = value;
            this.unit = unit;
            this.start = start;
        }
    }

    /** "20팩 + 2팩 증정" → 같은 단위면 앞의 수량에 더한다. 단위가 다르면 무시(다른 상품일 수 있음). */
    private static void addCount(List<Count> counts, Count c, boolean plus) {
        if (!plus) {
            counts.add(c);
            return;
        }
        if (counts.isEmpty()) {
            return;
        }
        Count last = counts.get(counts.size() - 1);
        if (java.util.Objects.equals(last.unit, c.unit)) {
            last.value += c.value;
        }
    }

    private static void dropRedundantTotal(List<Count> counts) {
        if (counts.size() < 3) {
            return;
        }
        for (int i = 0; i < counts.size(); i++) {
            double others = 1;
            for (int j = 0; j < counts.size(); j++) {
                if (j != i) {
                    others *= counts.get(j).value;
                }
            }
            if (Math.abs(counts.get(i).value - others) < 1e-9) {
                counts.remove(i);
                return;
            }
        }
    }

    private static Result finish(Type type, double total) {
        if (!(total > 0) || total > MAX_TOTAL) {
            return Result.failed("총량이 비정상: " + total);
        }
        return Result.ok(type, total);
    }

    private static String normalize(String title) {
        String s = title.toLowerCase(Locale.ROOT);
        s = s.replace("㎏", "kg").replace("㎖", "ml").replace("ℓ", "l");
        s = s.replace('×', 'x').replace('＊', 'x').replace('*', 'x').replace('＋', '+');
        // 1,000매 → 1000매
        s = s.replaceAll("(?<=\\d),(?=\\d{3})", "");
        // "3개월분" 같은 기간 표기는 수량이 아니다
        s = s.replaceAll("\\d+\\s*개월", " ");
        // "100gx20", "팩x2" → 공백 정리
        s = s.replaceAll("(?<=[0-9gl가-힣])\\s*x\\s*(?=\\d)", " x ");
        return s;
    }

    private static char prevNonSpace(String s, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char ch = s.charAt(i);
            if (!Character.isWhitespace(ch)) {
                return ch;
            }
        }
        return '\0';
    }

    private static boolean isAfterX(String s, int index) {
        int i = index - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        if (i < 0 || s.charAt(i) != 'x') {
            return false;
        }
        // "mx 20" 처럼 영문자 끝의 x 는 곱하기가 아니다
        return i == 0 || !(s.charAt(i - 1) >= 'a' && s.charAt(i - 1) <= 'z');
    }
}
