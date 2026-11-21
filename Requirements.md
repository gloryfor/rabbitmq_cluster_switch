# DNS 변경으로 RabbitMQ 클러스터 스위치하기

DNS를 변경하여 RabbitMQ 클러스터를 스위칭하도록 로컬에서 테스트해보려고 합니다. 
어플리케이션은 java로 구동하며 spring-boot 3.2.4 버전을 사용하려고 합니다.

---

## 1. 환경구성 
- RabbitMQ는 3.13.7 버전을 사용합니다.
- 어플리케이션은 spring-boot 3.2.4 버전을 사용합니다. 
- 로컬 DNS 전환 시뮬레이션을 위해 CoreDNS(`coredns/coredns:1.11.1`)와 다중 노드 RabbitMQ 클러스터 A/B(각 3노드)를 Docker Compose로 구성했습니다.

---

## 2. 아키텍처 및 구성 요소

```text
[rabbit-app (Spring Boot 3.2.4)] ──(DNS 질의: rabbitmq.cluster.local)──> [rmq-coredns (172.28.0.53)]
        │                                                                      │
        │  AMQP (5672)                                           hosts 파일 기반 A 레코드 반환
        ├──────────────────────────────────────────────────────────┐           (TTL 1s, Reload 1s)
        ▼                                                          ▼
[Cluster A (3 Nodes)]                                     [Cluster B (3 Nodes)]
 - rmq-a-node1 (172.28.1.11)                               - rmq-b-node1 (172.28.2.11)
 - rmq-a-node2 (172.28.1.12)                               - rmq-b-node2 (172.28.2.12)
 - rmq-a-node3 (172.28.1.13)                               - rmq-b-node3 (172.28.2.13)
```

| 구분 | 컨테이너명 | 내부 IP | 호스트 포트 | 비고 |
| :--- | :--- | :--- | :--- | :--- |
| **CoreDNS** | `rmq-coredns` | `172.28.0.53` | `1053` (UDP/TCP) | `docker/coredns/hosts` 변경 시 1초 내 자동 반영 |
| **Cluster A - Node 1** | `rmq-a-node1` | `172.28.1.11` | AMQP `5672` / UI `15672` | `cluster_name = cluster-a` |
| **Cluster A - Node 2** | `rmq-a-node2` | `172.28.1.12` | AMQP `5673` / UI `15673` | `rabbit@rmq-a-node1`과 클러스터링 |
| **Cluster A - Node 3** | `rmq-a-node3` | `172.28.1.13` | AMQP `5674` / UI `15674` | `rabbit@rmq-a-node1`과 클러스터링 |
| **Cluster B - Node 1** | `rmq-b-node1` | `172.28.2.11` | AMQP `5675` / UI `15675` | `cluster_name = cluster-b` |
| **Cluster B - Node 2** | `rmq-b-node2` | `172.28.2.12` | AMQP `5676` / UI `15676` | `rabbit@rmq-b-node1`과 클러스터링 |
| **Cluster B - Node 3** | `rmq-b-node3` | `172.28.2.13` | AMQP `5677` / UI `15677` | `rabbit@rmq-b-node1`과 클러스터링 |
| **Spring Boot App** | `rmq-switch-app` | `172.28.3.10` | HTTP `8080` | DNS 서버 `172.28.0.53` 사용 |

- RabbitMQ 관리 콘솔 접속 계정(A/B 공통): `guest` / `guest`
  - Cluster A UI: http://localhost:15672 / http://localhost:15673 / http://localhost:15674
  - Cluster B UI: http://localhost:15675 / http://localhost:15676 / http://localhost:15677

### 프로젝트 주요 구조 및 사전 요구사항
- **사전 요구사항**: Docker Desktop (Docker Compose), Java 17 (Gradle toolchain), Gradle Wrapper `8.7` (Spring Boot `3.2.4` 호환)
- **주요 디렉토리 및 파일**:
  - `docker-compose.yml`, `Dockerfile`: CoreDNS, 3노드 RabbitMQ 클러스터 A/B, JDK 17(`eclipse-temurin:17-jdk`) 기반 Spring Boot 앱 컨테이너 정의 및 `./heapdumps` 볼륨 마운트
  - `docker/coredns/Corefile`, `docker/coredns/hosts`: 로컬 DNS(`rabbitmq.cluster.local`) A 레코드 설정
  - `docker/rabbitmq/cluster-a/rabbitmq.conf`, `docker/rabbitmq/cluster-b/rabbitmq.conf`: `classic_config` 기반 각 3노드 자동 클러스터링 설정
  - `scripts/switch-dns.sh`: DNS 타겟 전환 및 상태 확인 스크립트 (`a`, `b`, `status`)
  - `scripts/collect-heapdump.sh`: 실행 중인 애플리케이션의 Heap Dump(`.hprof`) 수집 스크립트 (`--all`, `-o`, `--local`)
  - `build.gradle`, `gradle/wrapper/gradle-wrapper.properties`: Spring Boot `3.2.4` + Gradle `8.7` 빌드 및 `bootRun` JVM DNS 캐시 비활성화 설정
  - `src/main/resources/application.yml`: Spring Boot 표준 `spring.rabbitmq.addresses` 프로퍼티 설정
  - `src/main/java/com/example/rmqswitch/`: `ConnectionListener` 등록, Quorum Queue 선언, DNS 변경 감지 알림(`DnsSwitchWatchdog`), Producer/Consumer 및 REST API 소스

---

## 3. 핵심 구현 포인트

1. **Spring Boot 기본 설정(`spring.rabbitmq.addresses`) 및 내부 기본 `DnsRecordIpAddressResolver` 동작**:
   - `application.yml`에 `spring.rabbitmq.addresses: ${APP_RABBITMQ_ADDRESSES:rabbitmq.cluster.local:5672}`를 비롯해 `username`, `password`, `requested-heartbeat: 5s`, `connection-timeout: 3s`를 설정해 Spring Boot `RabbitAutoConfiguration`이 생성하는 기본 빈(`CachingConnectionFactory`, `RabbitTemplate`, `AmqpAdmin`, `SimpleRabbitListenerContainerFactory`)을 그대로 사용합니다.
   - `addresses`에 단일 도메인 주소 1개(`rabbitmq.cluster.local:5672`)만 지정되면 하위 `amqp-client`의 `ConnectionFactory.createAddressResolver(...)`가 내부적으로 공식 **`DnsRecordIpAddressResolver`**를 자동 선택해, 연결 또는 재연결 시마다 `InetAddress.getAllByName("rabbitmq.cluster.local")`로 모든 노드 IP(3개)를 `ResolvedInetAddress` 목록으로 조회합니다.
2. **JVM DNS 캐시 비활성화**:
   - `RmqSwitchApplication`의 `static` 블록(`Security.setProperty("networkaddress.cache.ttl", "0")`, `networkaddress.cache.negative.ttl=0`)과 `Dockerfile`의 `ENTRYPOINT`, `build.gradle`의 `bootRun` 태스크에 `-Dsun.net.inetaddr.ttl=0 -Dsun.net.inetaddr.negative.ttl=0` JVM 옵션을 적용해 매 조회마다 최신 DNS 응답을 받도록 설정했습니다.
3. **DNS 변경 감지 알림 전용 Watchdog (`DnsSwitchWatchdog`)**:
   - `RabbitConnectionDetails`에서 설정된 호스트(`rabbitmq.cluster.local`)를 읽어 2초 주기로 DNS를 폴링합니다. A 레코드 변경이 감지되면 커넥션을 강제로 리셋하지 않고 경고 로그(`[DNS Watchdog] DNS change detected ... Please restart the application to switch cluster.`)와 이벤트 이력만 남깁니다.
   - `/api/status` 응답의 `watchdog.dnsMismatchDetected` 플래그로 현재 연결된 브로커 IP와 최신 DNS 불일치(재기동 필요) 여부를 확인할 수 있습니다.
4. **재기동 스위칭 및 Quorum Queue 자동 선언**:
   - DNS 변경 후 애플리케이션(`rabbit-app`)을 재기동하면 새 클러스터에 연결되며, `RabbitAdmin`이 `QueueBuilder.durable("switch.test.queue").quorum().build()`(`x-queue-type=quorum`) 기반의 Quorum Queue와 Exchange/Binding을 자동 선언합니다.

---

## 4. 실행 방법

### 방식 A (권장): Docker Compose 통합 실행
CoreDNS(`172.28.0.53`)를 포함한 전체 DNS 스위칭 테스트는 Docker Compose 통합 실행을 권장합니다.

```bash
docker-compose up -d --build
```

클러스터 노드 수나 큐 타입 변경 후 재구성하거나 볼륨·클러스터 상태를 초기화하려면 아래 명령으로 내린 뒤 다시 기동하세요:

```bash
docker-compose down -v && docker-compose up -d --build
```

모든 컨테이너가 `Up` / `healthy` 상태가 되었는지 확인합니다:

```bash
docker-compose ps
```

### 방식 B: 로컬 Gradle 실행 (`./gradlew clean build`, `./gradlew clean bootRun`)
로컬 JVM에서 직접 애플리케이션만 빌드하거나 기동해 확인할 때는 다음 순서로 실행합니다.

1. **빌드 검증**:
   ```bash
   ./gradlew clean build
   ```
2. **8080 포트 충돌 방지**:
   Docker로 `rabbit-app` 컨테이너가 이미 실행 중이라면 먼저 중지합니다.
   ```bash
   docker-compose stop rabbit-app
   ```
3. **로컬 실행**:
   호스트 OS에서는 Docker 내부 CoreDNS(`172.28.0.53`)가 기본 DNS가 아니므로, 환경 변수로 접속할 브로커 주소를 지정하거나 `/etc/hosts`에 `rabbitmq.cluster.local`을 매핑한 뒤 실행합니다.
   ```bash
   # 예: Docker로 띄운 Cluster A Node 1(localhost:5672)에 직접 연결 테스트
   APP_RABBITMQ_ADDRESSES=localhost:5672 ./gradlew clean bootRun
   ```

---

## 5. 클러스터, Quorum Queue 및 실시간 모니터링 방법

### 5.1 RabbitMQ 3노드 클러스터 및 Quorum Queue 상태 확인
Cluster A와 Cluster B가 각각 3개 노드(`node1`, `node2`, `node3`)로 정상 클러스터링되었는지 확인합니다:

```bash
docker exec rmq-a-node1 rabbitmqctl cluster_status
docker exec rmq-b-node1 rabbitmqctl cluster_status
```

각 클러스터에 `switch.test.queue`가 `quorum` 타입과 3개 멤버(`members`)로 생성되었는지 확인합니다:

```bash
docker exec rmq-a-node1 rabbitmqctl list_queues name type leader members
docker exec rmq-b-node1 rabbitmqctl list_queues name type leader members
```

### 5.2 CoreDNS 응답 및 hosts 파일 확인 (선택)
호스트에서 1053 포트로 CoreDNS에 직접 질의하거나 스크립트로 현재 `docker/coredns/hosts` 내용을 확인할 수 있습니다:

```bash
./scripts/switch-dns.sh status
dig @127.0.0.1 -p 1053 rabbitmq.cluster.local +short
```

### 5.3 실시간 앱 로그 및 상태 API 확인
테스트를 진행하기 전에 **새 터미널 창을 열어** 애플리케이션 로그를 실시간으로 확인하면 DNS 변경 감지 로그와 재기동 후 클러스터 전환을 한눈에 볼 수 있습니다:

```bash
docker logs -f rmq-switch-app
```

상태 조회 API를 통해서도 설정된 `configuredAddresses`, 현재 연결된 클러스터, DNS IP 목록, `dnsMismatchDetected` 상태, 클러스터별 송수신 건수, 스위칭 이력을 JSON으로 확인할 수 있습니다:

```bash
curl -s http://localhost:8080/api/status | python3 -m json.tool
```

---

## 6. 직접 해보는 테스트 시나리오

애플리케이션은 실행 중일 때 **2초마다 자동으로 테스트 메시지를 발행(`PRODUCER`)하고 수신(`CONSUMER`)**합니다.
초기 상태에서는 DNS가 Cluster A(`172.28.1.11`, `172.28.1.12`, `172.28.1.13`)를 가리키고 있습니다.
*(필요 시 `curl -s -X POST http://localhost:8080/api/messages`로 단건 메시지를 즉시 수동 발행할 수도 있습니다.)*

### 테스트 1: DNS 변경 후 Watchdog 알림 확인 및 애플리케이션 재기동으로 스위칭

1. **현재 상태 확인** (`cluster-a`에 연결되어 있는지 확인):
   ```bash
   curl -s http://localhost:8080/api/status | python3 -m json.tool
   ```
   - `configuredAddresses`가 `"rabbitmq.cluster.local:5672"`, `activeConnection.clusterName`이 `"cluster-a"`, `watchdog.dnsMismatchDetected`가 `false`입니다.

2. **DNS를 Cluster B로 전환**:
   ```bash
   ./scripts/switch-dns.sh b
   ```

3. **Watchdog 알림 및 기존 연결 유지 확인** (약 2~4초 후):
   - 로그(`docker logs -f rmq-switch-app`)에 다음 경고 메시지가 출력되지만 기존 커넥션은 리셋되지 않고 `cluster-a` 연결이 유지됩니다:
     - `[DNS Watchdog] DNS change detected for [rabbitmq.cluster.local]: [172.28.1.11, 172.28.1.12, 172.28.1.13] -> [172.28.2.11, 172.28.2.12, 172.28.2.13]. Please restart the application to switch cluster.`
   - `/api/status` 확인 시 `liveDnsIps`는 Cluster B IP 목록이지만 `activeConnection.clusterName`은 `"cluster-a"`이고, `watchdog.dnsMismatchDetected`가 `true`로 표시됩니다:
     ```bash
     curl -s http://localhost:8080/api/status | python3 -m json.tool
     ```

4. **애플리케이션 재기동으로 클러스터 스위칭**:
   ```bash
   docker-compose restart rabbit-app
   ```

5. **전환 결과 확인** (재기동 후 3~5초 뒤):
   - 로그에서 `Attempting to connect to: [rabbitmq.cluster.local:5672]` 후 `[AMQP CONNECTED] cluster=cluster-b, broker=172.28.2.11:5672` (또는 `.12`, `.13`)가 출력되고 `[PRODUCER]` / `[CONSUMER]`가 `cluster-b`에서 동작합니다.
   - Cluster B에 `switch.test.queue`가 `quorum` 타입으로 자동 선언되었는지 및 API 상태(`dnsMismatchDetected: false`) 확인:
     ```bash
     docker exec rmq-b-node1 rabbitmqctl list_queues name type leader members
     curl -s -X POST http://localhost:8080/api/messages | python3 -m json.tool
     curl -s http://localhost:8080/api/status | python3 -m json.tool
     ```

---

### 테스트 2: DNS 변경 후 기존 클러스터 전체 중지(장애) 시 Spring 기본 재연결로 자동 Failover

DNS를 바꾼 뒤 애플리케이션을 재기동하기 전에 기존 클러스터 전체가 내려갔을 때, Spring AMQP 기본 재연결 과정에서 `DnsRecordIpAddressResolver`가 새 DNS를 조회해 다른 클러스터로 자동 Failover 되는지 검증합니다.

1. **현재 `cluster-b`에 연결된 상태에서 DNS를 Cluster A로 변경**:
   ```bash
   ./scripts/switch-dns.sh a
   ```
   *(Watchdog 알림이 찍히며, 재기동 전까지는 기존 `cluster-b` 연결이 유지됩니다.)*

2. **Cluster B 컨테이너 3대 강제 중지**:
   ```bash
   docker-compose stop rmq-b-node1 rmq-b-node2 rmq-b-node3
   ```

3. **자동 Failover 확인** (3~5초 후):
   ```bash
   curl -s http://localhost:8080/api/status | python3 -m json.tool
   ```
   - 로그에 `[AMQP SHUTDOWN]` 직후 `Attempting to connect to: [rabbitmq.cluster.local:5672]`가 찍히며 `[AMQP CONNECTED] cluster=cluster-a`로 자동 복구됩니다.

4. **중지했던 Cluster B 복구**:
   ```bash
   docker-compose start rmq-b-node1 rmq-b-node2 rmq-b-node3
   ```

---

### 테스트 3: 클러스터 내부 단일 노드 장애 시 같은 클러스터의 다른 노드로 Failover

DNS가 Cluster A(`172.28.1.11`, `172.28.1.12`, `172.28.1.13`)를 가리키는 상태에서 현재 연결된 노드 1대만 중지했을 때, 3노드 Quorum Queue의 과반수(2/3)가 유지되면서 동일 클러스터의 살아있는 다른 노드로 재연결되는지 검증합니다.

1. **현재 연결된 브로커 IP 확인**:
   ```bash
   curl -s http://localhost:8080/api/status | python3 -m json.tool
   ```
   - 예: `activeConnection.clusterName`이 `"cluster-a"`, `activeConnection.brokerIp`가 `"172.28.1.11"`인 경우 대상 노드는 `rmq-a-node1`입니다. (`.12`면 `rmq-a-node2`, `.13`이면 `rmq-a-node3`)

2. **연결된 단일 노드 중지**:
   ```bash
   docker-compose stop rmq-a-node1
   ```

3. **동일 클러스터 내 다른 노드로 Failover 및 Quorum 유지 확인** (3~5초 후):
   ```bash
   curl -s http://localhost:8080/api/status | python3 -m json.tool
   docker exec rmq-a-node2 rabbitmqctl list_queues name type leader members
   ```
   - Spring 기본 `DnsRecordIpAddressResolver`가 3개 IP 목록을 반환하고 `amqp-client`가 순서대로 접속을 시도하여, 중지된 노드를 건너뛰고 `cluster-a`의 살아있는 다른 노드(`172.28.1.12` 또는 `172.28.1.13`)에 연결됩니다.
   - 3노드 Quorum Queue의 정족수(2/3)가 유지되므로 메시지 발행 및 소비가 중단 없이 계속 이어집니다.

4. **중지했던 노드 복구**:
   ```bash
   docker-compose start rmq-a-node1
   ```

---

## 7. 제공 API 요약

| Method | Endpoint | 설명 |
| :--- | :--- | :--- |
| `GET` | `/api/status` | 설정된 `configuredAddresses`, DNS 호스트/IP, 활성 연결 클러스터/브로커 IP, Watchdog 상태(`dnsMismatchDetected`), 송수신 통계 및 이력 조회 |
| `POST` | `/api/messages` | 테스트 메시지 1건 즉시 발행 (`source=manual-api`) |

---

## 8. 트러블슈팅

- **기존 classic 큐가 남아 있을 때 `PRECONDITION_FAILED - inequivalent arg 'x-queue-type'` 발생**:
  - 동일 이름의 큐(`switch.test.queue`)가 이전에 `classic` 타입으로 생성되어 있으면 `quorum` 타입으로 재선언할 수 없습니다. `docker-compose down -v && docker-compose up -d --build`로 볼륨과 브로커 상태를 초기화한 뒤 재기동하세요.
- **Gradle 9.x 사용 시 `NoSuchMethodError` (`LenientConfiguration.getArtifacts`) 발생**:
  - Spring Boot `3.2.4` 및 `io.spring.dependency-management` `1.1.4`는 Gradle 9.x와 호환되지 않습니다. 시스템 전역 `gradle`(예: 9.x) 대신 프로젝트에 포함된 **`./gradlew`** (Gradle `8.7` Wrapper)를 사용하세요.
- **로컬 `./gradlew clean bootRun` 실행 시 `SocketTimeoutException: Connect timed out` 발생**:
  - 호스트 OS에서는 Docker 네트워크의 CoreDNS(`172.28.0.53`)가 기본 DNS로 사용되지 않으므로 `rabbitmq.cluster.local`이 외부 DNS(또는 ISP 리다이렉션 IP)로 해석되어 연결 시간 초과가 발생할 수 있습니다.
  - 로컬 JVM에서 실행하려면 `APP_RABBITMQ_ADDRESSES=localhost:5672 ./gradlew clean bootRun`처럼 접속 대상 주소를 지정하거나 `/etc/hosts`에 매핑해 실행하고, 전체 DNS 스위칭 테스트는 `docker-compose up -d --build`로 진행하세요.

---

## 9. Heap Dump 수집 방법

`rabbit-app` 컨테이너는 `eclipse-temurin:17-jdk` 기반 이미지로 구동되며, `./heapdumps` 디렉토리가 `/app/heapdumps`로 마운트되어 있어 OOM 발생 시(`-XX:+HeapDumpOnOutOfMemoryError`)는 물론 실행 중에도 언제든 힙덤프를 수집할 수 있습니다.

```bash
# 1) 기본 수집 (live 객체 기준, ./heapdumps/heapdump_rmq-switch-app_<timestamp>.hprof 저장)
./scripts/collect-heapdump.sh

# 2) unreachable(dead) 객체 포함 전체 힙덤프 수집 (jcmd GC.heap_dump -all)
./scripts/collect-heapdump.sh --all

# 3) 별도 디렉토리에 저장
./scripts/collect-heapdump.sh -o /tmp/dumps

# 4) 로컬 JVM(./gradlew clean bootRun) 프로세스에서 수집
./scripts/collect-heapdump.sh --local
```

---

## 10. 환경 중지 및 정리

테스트가 끝난 후 컨테이너와 네트워크를 정리하려면 아래 명령을 실행하세요:

```bash
docker-compose down
```
