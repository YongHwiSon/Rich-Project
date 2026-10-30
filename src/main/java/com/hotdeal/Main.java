package com.hotdeal;

import com.hotdeal.parser.UnitPriceParser;
import com.hotdeal.parser.UnitPriceParser.Result;

/**
 * 진입점. 지금은 단가 파서를 손으로 시험해 보는 `parse` 명령만 있다.
 *
 *   java -jar target/hotdeal.jar parse "닭가슴살 100g 20팩 + 2팩 증정" 19900
 *
 * 수집/DB/내보내기 명령은 이후 단계에서 추가한다. (hotdeal-plan.md 9장)
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        if (args.length >= 2 && args[0].equals("parse")) {
            Result r = UnitPriceParser.parse(args[1]);
            if (!r.parsed()) {
                System.out.println("계산 불가: " + r.reason());
                return;
            }
            System.out.printf("종류=%s, 총량=%.1f%n", r.type().json, r.totalQuantity());
            if (args.length >= 3) {
                int price = Integer.parseInt(args[2].replace(",", ""));
                System.out.printf("단가=%.1f원 (%s)%n", r.unitPrice(price), r.type().json);
            }
            return;
        }
        System.out.println("사용법: java -jar hotdeal.jar parse \"<상품 제목>\" [가격]");
    }
}
