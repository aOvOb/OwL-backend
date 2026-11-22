# OwL backend

개인 음악 스트리밍 PWA "OwL"의 백엔드입니다. Kotlin, Spring Boot 4, Java 25.

## 하는 일

- 로그인: Gonic(Subsonic 서버) 계정으로 로그인합니다. 로그인 정보는 AES-GCM으로 암호화한 HttpOnly 쿠키에만 담깁니다(서버 세션 없음, 배포해도 로그인 유지).
- 보관함: 재생목록, 앨범, 아티스트, 검색, 스트리밍, 커버 이미지를 Gonic에서 가져와 전달합니다. 스트리밍은 Range 요청을 그대로 넘겨 탐색이 됩니다.
- 유튜브 뮤직: 검색과 차트(멜론, Apple Music, Billboard, YouTube)는 차트 서비스에서, 다운로드는 Yubal에 요청합니다.

## API

| 경로 | 설명 |
|---|---|
| `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/me` | 로그인 |
| `GET /api/home` | 차트 재생목록, 내 재생목록, 최근 앨범 |
| `GET /api/playlists[/{id}]`, `/api/albums[/{id}]`, `/api/artists[/{id}]` | 보관함 |
| `GET /api/search?q=` | 보관함 검색 |
| `GET /api/stream/{id}`, `GET /api/cover/{id}` | 오디오, 커버 |
| `GET /api/charts[?key=]` | 차트 전체 목록(보관 여부, 다운로드 중 여부 포함) |
| `GET /api/yt/search?q=` | 유튜브 뮤직 검색 |
| `POST /api/yt/download`, `POST /api/yt/download/bulk`, `GET /api/yt/jobs` | 다운로드 |

## 설정

모든 비밀값은 서버의 환경 변수로만 넣습니다. 레포에는 넣지 않습니다.

| 변수 | 설명 |
|---|---|
| `OWL_SESSION_KEY` | 쿠키 암호화 키. 32바이트 난수의 base64 (`openssl rand -base64 32`) |
| `OWL_GONIC_URL`, `OWL_YUBAL_URL`, `OWL_SEARCH_URL` | 내부 서비스 주소(기본값은 서버 docker 네트워크 기준) |

## 빌드와 배포

```
./gradlew build
```

`main`에 푸시하면 GitHub Actions가 arm64 이미지를 빌드해 서버로 보냅니다. 서버는 blue/green 두 컨테이너를 번갈아 띄우고, 새 버전이 헬스체크를 통과한 뒤에 옛 버전을 끕니다(무중단). 배포 키는 서버에서 배포 명령 하나만 실행할 수 있게 제한돼 있습니다.
