# 배포

로컬에서는 멀쩡한데 배포에서만 터지는 것들을 모아 둔다.

---

## 1. 빌드가 툴체인에서 실패하는 경우

```
Cannot find a Java installation on your machine (Linux ... amd64) matching:
{languageVersion=17, vendor=any vendor, ...}.
Toolchain download repositories have not been configured.
```

Gradle 툴체인은 **이미 설치된** JDK 중에서만 고른다. 개발 PC 에는 17 이 있어 통과하지만
배포 컨테이너에는 없어서 여기서 끝난다. 코드 문제가 아니다.

`Back/settings.gradle` 의 foojay 리졸버가 이걸 해결한다 — 맞는 JDK 가 없으면 빌드 중에
받아온다.

```groovy
plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}
```

> 빌드 컨테이너에서 `api.foojay.io` 로 나가는 HTTPS 가 막혀 있으면 이 방법도 실패한다.
> 그때는 플랫폼 쪽에서 JDK 17 을 직접 지정해야 한다.

빌드 명령은 그대로 둔다.

```bash
./gradlew clean build -x check -x test -Pproduction
```

---

## 1-2. 빌드는 됐는데 `java` 가 사용법만 출력하고 죽는 경우

```
ls: cannot access '*/build/libs/*jar': No such file or directory
Usage: java [options] <mainclass> [args...]
```

플랫폼이 자동 생성하는 실행 명령이 와일드카드로 jar 를 찾다가 아무것도 못 찾은 것이다.
`java` 가 인자 없이 실행되니 사용법을 뱉고 끝난다. 걸리는 지점이 둘이었다.

| | 문제 | 결과 |
|---|---|---|
| 경로 | 그 패턴은 **멀티모듈**(루트 + 서브프로젝트) 배치를 가정한다 | 배포 루트가 `Back/` 이면 jar 는 `build/libs/` 에 바로 생겨 매칭 실패 |
| 이름 | 경로를 고쳐도 jar 가 둘이면 `ls` 정렬상 `-plain.jar` 가 먼저다 | `no main manifest attribute` — 껍데기 jar 라 실행 불가 |

`build.gradle` 에서 산출물을 하나로 고정해 둘 다 없앴다.

```groovy
tasks.named('bootJar') { archiveFileName = 'app.jar' }
tasks.named('jar')     { enabled = false }          // plain jar 를 만들지 않는다
```

실행 명령은 와일드카드 없이 이 한 줄이다. `Back/Procfile` 과 `Back/railway.json` 에
같은 내용이 들어 있다.

```bash
java -jar build/libs/app.jar
```

> 플랫폼 UI 에서 Start Command 를 직접 입력해 둔 게 있으면 **그쪽이 파일보다 우선한다.**
> 배포가 계속 같은 증상이면 UI 설정이 남아 있는지 먼저 확인할 것.

산출물은 `Back/build/libs/app.jar` 하나다.

---

## 2. 앱은 떴는데 헬스체크가 계속 실패하는 경우

PaaS 는 `PORT` 를 주입하고 **그 포트로만** 트래픽을 보낸다. 8080 에 붙어 있으면 로그에
`Started BackApplication` 까지 정상으로 찍히고도 외부에서는 아무것도 닿지 않는다.

```yaml
server:
  port: ${PORT:${SERVER_PORT:8080}}
```

로컬에서 포트를 바꿀 때는 `SERVER_PORT` 를 그대로 쓴다.

헬스체크 경로는 **`/api/health`** 로 지정한다. 응답으로 실제 모드까지 확인할 수 있다.

```json
{ "status": "ok", "llmMode": "live", "musicMode": "jamendo", "activeSessions": 0 }
```

`llmMode` 가 `mock` 이면 `OPENAI_API_KEY` 가 안 들어간 것이고,
`musicMode` 가 `synth` 면 `JAMENDO_CLIENT_ID` 가 안 들어간 것이다.
**둘 다 없어도 앱은 정상 기동한다** — 폴백으로 전체 흐름이 돌아가므로 배포 실패로
보이지 않는다. 반드시 이 응답으로 확인할 것.

---

## 3. 환경변수

`Back/application-local.yaml` 은 gitignore 대상이라 배포 환경에는 없다. 플랫폼의
환경변수로 넣는다. **키를 저장소에 커밋하지 말 것.**

| 변수 | 필수 | 없으면 |
|---|---|---|
| `OPENAI_API_KEY` | ○ | mock 모드 — 폴백 프리셋으로만 동작 |
| `JAMENDO_CLIENT_ID` | ○ | 실제 음원 없이 절차적 앰비언스만 재생 |
| `PORT` | 플랫폼이 주입 | 8080 |
| `CORS_ALLOWED_ORIGINS` | 프론트가 다른 도메인일 때만 | 로컬 출처만 열림 |

---

## 4. 프론트엔드

백엔드 jar 에는 프론트가 들어 있지 않다. 두 가지 방법이 있다.

### (A) 같은 서버에서 서빙 — 권장

`Front` 를 빌드해 `Back/src/main/resources/static/` 에 넣으면 백엔드 하나만 배포하면 된다.

```bash
cd Front && npm ci && npm run build
cp -r dist/* ../Back/src/main/resources/static/
```

- 동일 출처라 **CORS 설정이 필요 없다**
- 고객 화면 `/` 과 직원 화면 `/staff` 가 한 주소에서 열린다
- 경로 기반 라우팅이라 `/staff` 새로고침 시 서버가 `index.html` 을 돌려줘야 한다
  (SPA 폴백 필요)

### (B) 프론트를 따로 배포

```bash
VITE_API_BASE=https://<백엔드주소>/api npm run build
```

그리고 백엔드에 프론트 출처를 열어 준다.

```
CORS_ALLOWED_ORIGINS=https://<프론트주소>
```

> **직원 화면은 이 방법으로 동작하지 않는다.** CORS 는 `/api/health` 와
> `/api/mirror/**` 만 열려 있고 `/api/staff/**` 는 열려 있지 않다. 추천을 고객 출처에서
> 부를 수 없게 한 설계라, 직원 화면은 백엔드와 같은 출처에서 띄워야 한다.

---

## 5. 카메라

`getUserMedia` 는 **`localhost` 또는 https 에서만** 열린다. 배포 주소가 https 면 문제없다.
http 로 접속하면 동의 화면까지는 가고 촬영에서 멈춘다.

---

## 6. 세션은 메모리에 있다

`SessionStore` 는 인메모리다. **인스턴스를 재시작하면 진행 중이던 세션이 전부 사라지고,
2대 이상으로 늘리면 직원 화면과 고객 화면이 서로 다른 인스턴스에 붙어 응대가 성립하지
않는다.** 시연 규모에서는 1대로 고정한다.
