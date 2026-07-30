# Idea2Strategy Backend

Idea2Strategy의 사용자 API, 운영 작업, 알림, 관리자 MCP를 담당하는 Java/Spring Boot 저장소입니다.

## 책임

- 계정·인증·권한
- 전략 작성·검증·출시와 잠긴 버전 관리
- 봇 제어, 방·평가·성과 조회
- 운영 배치, 알림·Outbox 처리
- RBAC 기반 관리자 MCP

전략 실시간 평가·가상 체결은 `trading-engine`, 백테스트 계산은 `backtest-engine`, 시장 데이터 가공은 `data-pipeline`의 책임입니다.

예정 구조는 Gradle 멀티프로젝트입니다.

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

