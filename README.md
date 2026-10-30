# 핫딜 단가표

핫딜을 **개당 / 100g당 / 100ml당 단가**와 **평소가 대비**로 비교해 보여주는 반응형 웹.
설계와 결정 사항은 [`hotdeal-plan.md`](hotdeal-plan.md) 참고.

## 지금 들어 있는 것

| 경로 | 내용 |
|---|---|
| `src/main/java/com/hotdeal/parser/UnitPriceParser.java` | 제목에서 수량·용량을 뽑아 단가를 계산하는 규칙 기반 파서 |
| `src/test/java/com/hotdeal/parser/UnitPriceParserTest.java` | 파서 테스트 (계산되는 21건, 계산 불가여야 하는 10건 등) |
| `src/main/java/com/hotdeal/Main.java` | `parse` 명령으로 파서를 손으로 시험 |
| `docs/` | 반응형 목업 (모바일 카드 ↔ 768px 이상 표). `data.json`은 **더미 샘플** |

아직 없는 것: 커뮤니티 수집기, SQLite 저장, 실제 `data.json` 생성, GitHub Actions.

## 실행

```powershell
# 테스트
mvn test

# 실행형 jar 만들기
mvn package

# 파서 시험 (한글이 깨지면 먼저: chcp 65001)
java -jar target/hotdeal.jar parse "닭가슴살 100g 20팩 + 2팩 증정" 19900
```

목업은 `docs/index.html`을 VS Code에서 **우클릭 → Open with Live Server** 로 엽니다.
파일을 더블클릭해 열면 브라우저가 `data.json` 읽기를 막아서 목록이 비어 보입니다.

## 파서 규칙 요약

- 잘못된 단가보다 **"계산 불가"** 가 낫다. 조금이라도 모호하면 계산하지 않는다.
- `100g x 20팩`, `20팩 + 2팩 증정`, `1+1`, `1박스(20개)`, `총 10L` 등을 처리한다.
- 용량이 수량 뒤에 오는 `5개입 600g`은 총량인지 개당인지 알 수 없어 계산하지 않는다.
- 한계: `5G 스마트폰`처럼 식품·생활용품이 아닌 제목은 오탐할 수 있다 (카테고리 필터는 이후 단계).

## GitHub Pages로 올리기

저장소 Settings → Pages → Source를 **Deploy from a branch**, 브랜치 `main`, 폴더 `/docs` 로 지정.

## 주의

- 저장소가 public 이므로 API 키 등 비밀값은 커밋하지 않는다. (`.gitignore` 참고)
- 외부 링크는 `rel="noopener noreferrer"`. 제휴(수익) 링크를 쓰게 되면 `docs/app.js`의 `REL_COMMUNITY`를 `sponsored` 포함으로 바꾸고, `docs/index.html`의 주석 자리에 대가성 문구를 넣는다.
