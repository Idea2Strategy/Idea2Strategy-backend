# Backend 개발 가이드

## 모듈 경계

- `apps`: 독립 실행되는 Spring Boot 애플리케이션입니다. Controller, Listener, Batch Job, 실행 설정을 둡니다.
- `modules/backend-domain`: 가능한 한 Spring 의존성이 없는 도메인 규칙입니다.
- `modules/backend-application`: `@Service`, Use Case, Port를 둡니다.
- `modules/backend-persistence`: JPA Command 구현과 jOOQ Query 구현을 둡니다.
- `modules/backend-messaging`: Queue, Redis, Outbox 어댑터를 둡니다.
- `modules/backend-common`: 정말 공통인 기술 요소만 둡니다.

## 데이터 접근

- 주 변경 스키마: `identity`, `strategy`, `competition`, `performance`, `operations`, `bot`의 제어 영역
- Command 기본: JPA
- 복합 Query 기본: jOOQ
- DB 구조와 마이그레이션의 정본: 루트 저장소 DBML과 Flyway
- `ddl-auto=validate`를 사용하며 이 저장소가 독자적으로 스키마를 생성하지 않습니다.

## Git Flow

- `develop`: 기본 개발·통합 브랜치
- `feature/*`, `fix/*`, `docs/*`, `chore/*`: `develop`에서 분기하고 `develop`으로 병합
- `release/*`: 정식 릴리스 준비
- `main`: v1.0.0부터 검증된 정식 릴리스만 병합
- `hotfix/*`: 정식 릴리스 이후 `main`에서 분기하고 `main`과 `develop` 양쪽에 반영

`main`에서 직접 개발하거나 미완성 기능을 병합하지 않습니다.

