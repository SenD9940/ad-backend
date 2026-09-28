# admin-api

회원, 워크스페이스, 플랫폼 연결, 관리자 변경 이력을 관리하는 별도 API 서버다. 기본 포트는 `8481`이다. 브라우저의 관리자 로그인 화면은 `http://localhost:3400/admin/login`이며, [로컬 실행·최초 관리자 지정 안내](../docs/admin-local-login.md)를 참고한다.

일반 `api` 모듈과 같이 도메인별로 `controller → business → service`를 구성한다. 요청·응답 DTO는 `controller/model`, 변환 로직은 `converter`에 두고, Entity와 Repository는 공유 `db` 모듈에서 관리한다.

```text
com.orinan.adminapi
├── annotation                 # @Business, @Converter
├── common
│   ├── api                    # Api, Result, PageResponse, 페이지 요청 검증
│   └── exception              # 관리자 업무 예외
├── config                     # jpa, objectmapper, security
├── domain
│   ├── auth                   # 관리자 로그인·세션
│   ├── token                  # 관리자 토큰 발급·검증·세션 폐기
│   ├── user                   # 회원 관리
│   ├── overview               # 운영 현황
│   ├── workspace              # 워크스페이스·멤버·초대 관리
│   ├── platformconnection     # 플랫폼 연결 관리
│   ├── support                # 고객 승인·수납 기록·지원 세션·작업 이력
│   └── audit                  # 감사 이력
└── exceptionhandler
```

Controller는 요청을 검증하고 `Api.OK(...)`로 응답한다. Business는 작업 순서·권한·트랜잭션을 관리하고, Service는 Repository를 통해 데이터를 조회·변경하며, Converter가 응답 모델을 만든다. DB 조회용 projection에는 관리자 HTTP 모델을 참조하지 않는다. 관리자 JWT와 접근 제한은 `admin-api` 안에서 독립적으로 유지한다.

토큰 도메인도 일반 `api`와 같은 계층으로 구성한다.

```text
domain/token
├── business/AdminTokenBusiness          # 발급·검증·폐기 진입점과 트랜잭션
├── service/AdminTokenService            # 현재 관리자·인증 버전 확인, 세션 폐기
├── converter/AdminTokenConverter        # 내부 토큰 DTO → 응답 모델
├── controller/model/AdminTokenResponse
├── model                               # 토큰·클레임·폐기 결과 DTO
├── ifs/AdminTokenHelperIfs
├── helper/AdminTokenHelper              # JWT 서명·파싱
└── exception                           # 토큰 오류 분류와 예외
```

`AdminAuthBusiness`는 로그인·감사 기록을 관리하며 토큰 작업은 `AdminTokenBusiness`에 위임한다. `AdminTokenService`만 Helper 인터페이스를 사용하고 토큰 일괄 만료는 `db.token.AdminTokenRepository`에서 수행한다.

실행 전 DB 마이그레이션과 최초 관리자 지정이 필요하다. API 목록, 요청 예시, 설정 및 배포 순서는 [관리자 API 문서](../docs/admin-api.md)를 참고한다.

기존 관리자 기능과 함께 고객 승인 기반 기술 지원을 제공한다. 수동 수납 확인과 15분 지원 세션, 고객 철회, 작업 기록, 화면 경로 및 별도 마이그레이션은 [기술 지원 운영 문서](../docs/support-operations.md)를 참고한다. 실제 Toss Payments 결제는 아직 연동하지 않았다.
