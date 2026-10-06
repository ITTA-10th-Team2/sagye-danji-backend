# Issue #54 구현 지시서

- 기준 이슈: https://github.com/ITTA-10th-Team2/sagye-danji-backend/issues/54
- 대상 프로젝트: `sagye-danji-backend`
- 목표: 단지 화면 연동 API, 계절별 기록 개수, 단일 이미지 기록 계약을 현재 프로젝트 컨벤션에 맞춰 구현한다.

## 1. 지시 우선순위

이 문서의 이미지 요구사항은 Issue #54에 작성된 다중 이미지 설명보다 우선한다.

1. 기록 하나에는 이미지가 **정확히 한 장**만 존재한다.
2. 기록 생성 요청은 이미지 배열을 받지 않는다.
3. 기록 생성 요청은 `imageId`와 `type`을 절대 받지 않는다.
4. 프론트는 업로드 완료 후 업로드 API 응답의 `objectKey` 한 개만 기록 API에 전달한다.
5. 서버가 `Record` 저장과 함께 `Image` 엔티티를 생성하고 생성된 `imageId`를 응답으로 내려준다.
6. 기록 수정도 이미지 배열을 받지 않는다. 새 `objectKey`가 전달되면 기존 한 장을 새 이미지로 교체한다.
7. 다중 이미지, 이미지 순서 변경, 기존 이미지 ID를 조합하는 기능은 구현하지 않는다.

## 2. URL과 objectKey 용어

사용자가 말한 “프론트에서 URL만 전달”은 API 계약에서는 `objectKey` 전달로 구현한다.

- `uploadUrl`: 파일 업로드에만 쓰는 5분 만료 Presigned URL이다. 기록 API로 전달하거나 DB에 저장하지 않는다.
- `objectKey`: 업로드된 객체의 안정적인 식별자다. 기록 생성·수정 API가 받는 값이다.
- `originalUrl`, `thumbnailUrl`: 조회 시 서버가 발급하는 만료 가능한 조회 URL이다. 응답에만 포함하고 DB에 저장하지 않는다.

프론트 흐름은 다음과 같다.

1. `POST /api/images/presigned-url` 호출
2. 응답의 `uploadUrl`과 `requiredHeaders`로 파일 PUT
3. 같은 응답의 `objectKey`를 기록 생성 또는 수정 요청에 포함
4. 서버가 객체 존재·소유권을 검증하고 `Image`를 생성

전체 URL에서 객체 키를 파싱하는 구현은 금지한다. 외부 URL·다른 회원 객체·만료 URL이 기록에 연결되지 않도록 기존 `ImageStorageService.validateUploadedObjects` 검증을 유지한다.

## 3. 작업 전 필수 분석

구현 전에 다음을 직접 확인하고 현재 코드가 달라졌다면 이 문서보다 현재 구조에 맞게 최소 변경한다.

- `.github`의 PR·이슈 및 커밋 컨벤션
- `RecordController`, `RecordCommandService`, `RecordQueryService`
- `Record`, `Image` 엔티티와 repository
- `RecordCreateRequest`, `RecordUpdateRequest`, 기록 조회 응답 DTO
- `ImageController`, `ImageStorageService`, 스토리지 client
- `SecurityConfig`, `ErrorCode`, `ApiResponse`, `ErrorResponse`
- 관련 단위·컨트롤러 테스트

기존 사용자의 변경과 관계없는 코드는 수정하지 않는다. 미추적 `.md` 파일은 별도 요청 없이 커밋하지 않는다.

## 4. 기능 A: 단일 이미지 기록 계약

### 4.1 기록 생성 요청

`POST /api/records`

요청은 다음 구조로 단순화한다.

```json
{
  "recordDate": "2026-10-06",
  "memo": "가을의 한 장면",
  "objectKey": "record-images/42/2026/10/example.jpg"
}
```

필드 규칙:

- `recordDate`: 기존 정책 유지. 생략 시 서울 기준 오늘
- `memo`: 기존 길이·nullable 정책 유지
- `objectKey`: 필수, 공백 불가, 최대 512자
- `images`, `imageId`, `type`, `sortOrder`는 생성 요청에서 제거

서비스 처리 순서:

1. 회원·메모·`objectKey`를 검증한다.
2. 동일 `objectKey`가 다른 이미지에 연결됐는지 검사한다.
3. `ImageStorageService.validateUploadedObjects(memberId, List.of(objectKey))`로 객체 소유권과 실제 업로드 완료를 검증한다.
4. `Record`를 생성한다.
5. 생성된 Record에 연결된 Image 한 건을 생성한다.
6. record와 image를 같은 트랜잭션에서 flush한다.
7. 응답에는 서버가 발급한 `imageId`, `originalUrl`, `thumbnailUrl`을 포함한다.

이미지 메타데이터 중 다중 이미지 전용인 `sortOrder`는 API 계약에서 제거한다. DB 호환 때문에 즉시 제거하지 않는다면 서버 내부에서만 항상 0으로 설정하고 DTO에 노출하지 않는다.

### 4.2 PhotoSource 처리

현재 제품 요구에서 카메라·갤러리 출처를 사용하지 않고 프론트가 `objectKey`만 전달하므로, 거짓 의미의 기본값을 하드코딩하지 않는다.

다음 중 현재 DB 변경 전략에 맞는 한 가지를 선택하되, 최종 API에서는 `source`를 요구하지 않는다.

- 권장: `Image.source`와 `PhotoSource`를 사용처와 함께 제거하고 DB 컬럼도 제거한다.
- 호환 단계가 필요하면: 컬럼을 nullable/deprecated로 전환하고 신규 코드에서는 읽거나 응답하지 않는다.

`CAMERA` 또는 `GALLERY`를 임의 기본값으로 저장하는 구현은 금지한다.

### 4.3 기록 수정 요청

`PATCH /api/records/{recordId}`

```json
{
  "recordDate": "2026-10-07",
  "memo": "수정한 기록",
  "objectKey": "record-images/42/2026/10/replacement.jpg"
}
```

- `objectKey` 생략 또는 null: 기존 한 장을 유지한다.
- 새 `objectKey` 전달: 기존 이미지를 새 이미지로 교체한다.
- `images`, `type`, `imageId`, `sortOrder`를 받지 않는다.
- 새 객체의 검증을 모두 통과하기 전에는 기존 이미지를 삭제하지 않는다.
- 새 Image 저장과 DB 반영이 성공한 뒤 기존 객체 삭제를 `scheduleDeletionAfterCommit`으로 예약한다.
- 실패나 롤백 시 기존 이미지와 객체가 유지돼야 한다.
- 현재 기록에 연결된 이미지가 0건 또는 2건 이상이면 조용히 보정하지 말고 데이터 무결성 오류로 처리한다.

### 4.4 엔티티·DB 무결성

- `images.record_id`에 unique 제약을 추가해 기록당 최대 한 장을 DB에서도 보장한다.
- 기존 `uk_images_record_order`는 단일 이미지 계약에 맞춰 제거한다.
- `original_key` unique 제약은 유지한다.
- 기록 삭제 시 이미지 행과 스토리지 원본·썸네일 삭제 예약 정책은 유지한다.
- 프로젝트가 migration 도구를 사용한다면 명시적 migration을 작성한다.
- migration 도구가 없다면 엔티티 제약과 로컬 DB 반영 절차를 README 또는 PR 공유 사항에 명시한다.

기존 DB에 한 기록당 여러 이미지가 존재할 가능성이 있으면 unique 제약 적용 전에 진단 쿼리를 작성한다. 어떤 이미지를 남길지 임의로 결정하는 자동 삭제는 하지 않는다.

### 4.5 조회 응답

단일 이미지 계약에 맞춰 기록 응답을 singular 형태로 정리한다.

```json
{
  "id": 101,
  "recordDate": "2026-10-06",
  "season": "AUTUMN",
  "memo": "가을의 한 장면",
  "image": {
    "id": 501,
    "originalUrl": "https://...",
    "thumbnailUrl": "https://..."
  }
}
```

- `images`, `previewImages`, `imageCount`처럼 다중 이미지를 전제로 한 필드는 제거하거나 단일 `image`로 교체한다.
- 전체 목록, 계절 목록, 상세, 생성, 수정 응답에서 같은 이미지 DTO를 재사용한다.
- `thumbnailKey`가 없으면 `thumbnailUrl`은 `originalUrl`과 동일하게 반환한다.
- 객체 키는 응답에 노출하지 않는다.
- 페이지 내 이미지 조회는 record ID 목록으로 한 번에 가져와 DB N+1을 만들지 않는다.
- URL 발급 호출은 DB N+1과 구분하되, 동일 객체 키의 URL을 한 응답 조립 중 중복 발급하지 않는다.

## 5. 기능 B: 홈 기록 요약의 계절별 개수

기존 `GET /api/records/summary` 응답을 확장한다.

```json
{
  "recordCount": 12,
  "recordingDayCount": 84,
  "seasonRecordCounts": {
    "SPRING": 2,
    "SUMMER": 3,
    "AUTUMN": 7,
    "WINTER": 0
  }
}
```

요구사항:

- 기존 `recordCount`, `recordingDayCount` 의미를 변경하지 않는다.
- `SPRING`, `SUMMER`, `AUTUMN`, `WINTER` 네 키를 항상 반환한다.
- 기록이 없는 계절도 0으로 반환한다.
- 계절별로 반복 쿼리하지 않는다.
- 기존 summary native query에 조건부 집계를 추가해 전체 개수·최초 기록일·네 계절 개수를 한 번에 조회하는 방식을 우선한다.
- 빈 기록, 첫 기록 당일, 윤년·연도 경계에 대한 기존 테스트를 유지하고 계절별 0 포함 테스트를 추가한다.

## 6. 기능 C: 단지 스티커 배치

스티커는 기록과 별도 생명주기를 가지므로 신규 `jar` 도메인에서 관리한다.

### 6.1 API

#### 조회

`GET /api/jars/{year}/seasons/{season}/stickers`

- 인증 회원의 해당 연도·계절 배치를 조회한다.
- 저장된 레이아웃이 없으면 404가 아니라 빈 `items`를 반환한다.
- `zIndex ASC` 순으로 반환한다.
- 개인 데이터이므로 `Cache-Control: no-store`, `Pragma: no-cache`를 적용한다.

#### 전체 저장

`PUT /api/jars/{year}/seasons/{season}/stickers`

- 화면 최종 상태 전체를 스냅샷으로 받는다.
- 기존 항목을 부분 수정하지 않고 한 트랜잭션에서 전체 교체한다.
- 빈 목록은 모든 스티커를 제거한 상태다.
- 동일 요청 반복 결과가 같은 멱등 API로 만든다.

### 6.2 저장 모델

레이아웃 식별:

- 인증된 `member_id`
- `year`
- `season`
- `(member_id, year, season)` unique 제약

스티커 항목:

- `instanceId`: 프론트가 생성한 UUID 문자열
- `stickerType`: 서버 enum
- `xRatio`, `yRatio`: 캔버스 기준 0~1 정규화 좌표
- `scale`: 0.5~2.0
- `rotation`: -180~180
- `zIndex`: 겹침 순서

검증:

- year 2000~2100
- 단지당 최대 20개
- `instanceId` 중복 금지
- `zIndex` 중복 금지 및 0부터 연속되도록 검증
- null 항목 금지
- 회원 ID는 토큰에서만 획득하고 요청으로 받지 않는다.

픽셀 좌표는 저장하지 않는다. 프론트는 실제 캔버스 크기에 `xRatio`, `yRatio`를 곱해 위치를 복원한다.

스티커 이미지 파일은 프론트 정적 자산이며 서버는 스티커 종류와 배치만 저장한다.

## 7. Swagger·Javadocs

- 새 API와 변경 API 모두 `@Operation`, `@ApiResponses`, `@Schema`, `@Parameter`를 프로젝트 형식에 맞춰 작성한다.
- 성공 응답 Swagger 스키마는 실제 `ApiResponse<T>` JSON 래퍼를 반영한다.
- 생성 요청 Swagger 예시에는 `objectKey` 한 개만 있어야 한다.
- 수정 요청 Swagger 예시는 이미지 유지와 한 장 교체 두 경우를 제공한다.
- Swagger 어디에도 생성 요청의 `imageId`, `type`, 이미지 배열이 나타나면 안 된다.
- DTO, entity, repository, service, controller의 공개 역할과 중요한 불변식에 한국어 Javadocs를 작성한다.

## 8. 테스트 요구사항

### 기록·이미지

- 생성 요청에 `objectKey`가 없으면 400
- 생성 시 image ID 없이 Image 한 건이 생성됨
- 업로드되지 않았거나 다른 회원 경로의 objectKey 거부
- 이미 연결된 objectKey 중복 거부
- 생성 응답에 서버 생성 image ID와 조회 URL 포함
- 수정 시 objectKey 생략하면 기존 이미지 유지
- 수정 시 새 objectKey를 보내면 단일 이미지 교체
- 교체 실패·롤백 시 기존 이미지와 객체 유지
- 커밋 후에만 기존 스토리지 객체 삭제
- DB unique 제약으로 기록당 두 번째 이미지 저장 거부
- 목록 페이지에서 이미지 repository 일괄 조회 횟수 검증
- Swagger 생성 스키마에 `imageId`, `type`, `images`가 없음을 검증

### 홈 요약

- 네 계절 개수가 정확히 반환됨
- 0건 계절도 키와 0이 반환됨
- 전체 개수는 계절별 합과 일치
- 빈 회원 응답 검증

### 스티커

- 미저장 레이아웃은 빈 목록
- 저장 후 동일 연도·계절 조회 복원
- 다른 연도·계절과 격리
- 다른 회원과 격리
- 전체 교체와 빈 목록 삭제
- 좌표·배율·회전·개수·중복 검증
- 인증 없는 요청 거부

## 9. 구현 순서 및 커밋 분리

1. `refactor: 기록 이미지를 단일 이미지 계약으로 단순화`
2. `feat: 홈 요약에 계절별 기록 수 제공`
3. `feat: 단지 스티커 배치 조회 및 저장 구현`
4. `docs: 기록 및 단지 API Swagger 명세 보완`
5. `test: 단일 이미지와 단지 연동 API 테스트 추가`

실제 diff가 작으면 문서·테스트 커밋을 해당 기능 커밋에 포함할 수 있다. 기능과 무관한 포맷 변경은 섞지 않는다.

## 10. 완료 조건

- 기록 생성 요청에 `objectKey` 한 개만 존재하고 `imageId`, `type`, 이미지 배열이 없다.
- 기록당 Image가 정확히 한 건 생성되고 DB unique 제약으로 최대 한 건이 보장된다.
- 기록 수정은 image ID 없이 기존 한 장 유지 또는 새 objectKey 한 장 교체가 가능하다.
- 다중 이미지 전용 코드와 Swagger 계약이 제거된다.
- 홈 요약이 네 계절별 기록 개수를 0 포함해 제공한다.
- 연도·계절별 스티커 배치를 저장하고 다시 조회할 수 있다.
- 인증·소유권·입력 검증·캐시 방지 정책이 적용된다.
- 관련 테스트와 `./gradlew test`가 통과한다.
- 기존 미추적 지시서 파일은 커밋하지 않는다.

## 11. 금지 사항

- Presigned `uploadUrl` 또는 조회용 URL을 DB 식별자로 저장하지 않는다.
- URL 문자열에서 objectKey를 임의 파싱하지 않는다.
- 기록 생성 요청에서 image ID를 요구하지 않는다.
- 한 기록에 이미지 배열 또는 여러 Image를 생성하지 않는다.
- `CAMERA`·`GALLERY`를 의미 없이 하드코딩하지 않는다.
- 스티커 좌표를 기기 픽셀로 저장하지 않는다.
- 계절 수 집계를 네 번의 반복 쿼리로 구현하지 않는다.
- 다른 회원의 record, image, jar layout 존재 여부를 응답으로 누설하지 않는다.
