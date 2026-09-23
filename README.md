# GYEJOL-DANJI

## 로컬 실행

- Java 21, MySQL이 필요합니다. Gradle 9.4.0 Wrapper가 포함되어 있습니다.
- 프로젝트 이름: `GYEJOL-DANJI`
- 기본 패키지: `com.gyejoldanji`
- 실행 클래스: `GyejolDanjiApplication`

MySQL에 `gyejol_danji` 데이터베이스를 생성한 뒤, PowerShell에서 실제 접속 정보를 설정합니다.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:DB_URL = 'jdbc:mysql://localhost:3306/gyejol_danji'
$env:DB_USERNAME = 'your_db_username'
$env:DB_PASSWORD = 'your_db_password'
.\gradlew.bat bootRun
```

`.env` 파일은 자동으로 로드되지 않습니다. 위 환경변수 또는 IDE 실행 설정을 사용하세요.

- API 확인: http://localhost:8080/api/test/response
- Swagger: http://localhost:8080/swagger-ui/index.html
- 컴파일 및 JAR 생성: `.\gradlew.bat assemble testClasses`
- 테스트 실행: `.\gradlew.bat test` (위 MySQL 접속 설정 필요)

GitHub Project 연결과 팀 공유 문서 주소는 생성 후 설정하세요.

---

## Git Branch 전략

---

### main (배포)

---

- 최종 배포 가능한 상태만 유지
- dev에서 충분히 테스트된 내용만 Merge

### dev (개발)

---

- 다음 배포를 위해 개발된 기능들이 모이는 곳
- 항상 빌드 및 배포 가능한 상태 유지

### feat/#1 (기능)

---

- dev에서 분기하여 새로운 기능 개발
- 작업 완료 후 dev로 PR

---

## Branch 이름 컨벤션

---

- 형식: `{header}/{issue-number}`
- 예시: `feat/#5` `fix/#11` `refactor/#23`

## 💡 Issue & Pull Request (PR) 규칙

---

### ✅ Issue 체크리스트

---

- Assignees에 본인을 선택했나요?

---

### ✅ PR 체크리스트

---

- Reviewer에 팀원들을 선택
- Assignees에 본인을 선택
- 컨벤션에 맞는 Labels 선택
- Development에 이슈 연동
- Project 연동
- Merge 브랜치 확인 (dev)
- 컨벤션 준수
- 로컬 실행 시 에러 없음
- 팀원에게 PR 공유

### 📢 공유 및 Merge 조건

---

- 환경 설정 파일 변경 시 GYEJOL-DANJI 팀 공유 문서에 업데이트 (application.yml, .env 등)
    - GYEJOL-DANJI 팀 문서 URL: 추후 설정
- PR 생성 직후 디스코드에 링크 공유
- 1명 이상이 확인 후 리뷰 진행 (본인이나 다른 사람이 Merge)
- Merge: Squash and Merge 사용
    - 예시) #이슈번호 이슈제목 (#PR번호) 
    - #2 [Feat] 소셜 로그인 기능 구현 (#3)
- Merge 완료 후 브랜치 자동 삭제

---

## Commit Message 컨벤션

---

- 예시) Feat: 소셜 로그인 기능 구현

| Category | Type     | Description                    |
|--------|----------|--------------------------------|
| Feature | Feat     | 새로운 기능 추가                      |
| Bug | Fix      | 버그 수정                          |
| Bug | Hotfix   | 긴급 치명적 버그 수정                   |
| Task | Chore    | 빌드 업무, 패키지 설정, .gitignore 수정 등 |
| Task | Infra    | 인프라 관련                         |
| Task | Refactor | 코드 리팩토링 (기능 변경 없음)             |
| Task | Comment  | 주석 추가 및 변경                     |
| Task | Rename   | 파일/폴더명 수정 또는 이동                |
| Task | Remove   | 파일 삭제                          |
| Task | Init     | 프로젝트 초기 세팅                     |
| Task | Merge    | 브랜치 병합                         |
| Task | Test     | 테스트 코드 추가/수정                   |
| Task | Docs     | 문서 수정 (README, Wiki 등)         |

---

## Work Flow

---

### 1. 최신 코드 불러오기
- 작업 전 항상 `dev` 브랜치 최신 상태 유지
- 작업 중인 내용이 없을 때:
```
git pull origin dev     # 원격 dev 브랜치 내용 당겨오기
```
- 작업 중일 때 (Stash 활용):
```
git stash               # 작업 중인 코드 임시 저장
git pull origin dev     # 원격 dev 브랜치 내용 당겨오기
git stash pop           # 임시 저장했던 코드 다시 가져오기
```

### 2. 브랜치 생성
- 작업 목적에 맞는 독립 브랜치 생성 후 작업 시작
```
# 형식 : {type}/#{issue-number}
git checkout -b feat/#1
```
### 3. 커밋 (Commit)
```
- IDE Commit UI 활용
- git add . 또는 논리적 단위로 Commit
- 커밋 메시지 : Commit Message Convention 준수
```

### 4. 푸시 (Push)
- 작업 완료 후 브랜치를 원격 저장소에 업로드
```
git push origin {생성한-브랜치-명}
# 예: git push origin feat/#1
```

---

## 프로젝트 구조

```
com.gyejoldanji
├── GyejolDanjiApplication.java
│
├── domain
│
└── global
```
