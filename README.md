# Idea2Strategy Backend

Idea2Strategy의 사용자 API, 운영 작업, 알림, 관리자 MCP를 담당하는 Java/Spring Boot 저장소입니다.

## 책임

- 계정·인증·권한
- 전략 작성·검증·출시와 잠긴 버전 관리
- 봇 제어, 방·평가·성과 조회
- 운영 배치, 알림·Outbox 처리
- RBAC 기반 관리자 MCP

전략 실시간 평가·가상 체결은 `trading-engine`, 백테스트 계산은 `backtest-engine`, 시장 데이터 가공은 `data-pipeline`의 책임입니다.

## Production Basic catalog

활성 카탈로그 `basic-elements:2026-08-08`은 UI의 14개 Basic 블록을 모두 컴파일하며, 새 전략의
봉 주기는 `30m`, `1h`, `4h`, `1d`로 제한합니다. 실행 계약의 `$resolution`은 저장된 각 블록
파라미터로 해석되고, 한 전략에서 둘 이상의 주기를 혼용하면 릴리스 전에 거부됩니다.
이전 카탈로그와 migration은 이미 릴리스된 bot의 재현성을 위해 변경하지 않고 retired 상태로
보존합니다. 실제 계산과 owner-scoped 취소 API는 `backtest-engine`, 실시간 가상 주문 후보와
부분 포지션 sizing은 `trading-engine`이 담당합니다.

Gradle 멀티프로젝트 골격은 다음과 같습니다.

```text
apps/
  backend-api/
  backend-batch/
  backend-worker/
  admin-mcp/
modules/
  backend-domain/
  backend-application/
  backend-persistence/
  backend-messaging/
  backend-common/
```

구현 전에는 루트 조정 저장소의 `specs/`, `contracts/`, `db/schema.dbml`과 [DEVELOPMENT.md](DEVELOPMENT.md)를 먼저 확인합니다.

## 공통 골격 실행

Java 21과 Gradle 8.14.3을 사용합니다.

```text
gradle test
gradle :apps:backend-api:bootRun
gradle :apps:admin-mcp:bootRun
```

`backend-batch`와 `backend-worker`는 호스트 포트를 열지 않는 비웹 프로세스입니다. 로컬 전체 실행은 루트 저장소의 Compose `apps` 프로필을 사용합니다.

