# ip-block-filter

DB / XML / JSON / Java コードから読み込んだ IP（単体・CIDR）を、定期的にリロードしながらブロックする Servlet Filter。

- 対象: Tomcat 10.1 以降（`jakarta.servlet`）、Java 11 以降
- ライセンス: [MIT License](../LICENSE)
- 依存: `slf4j-api` のみ（ログの出力先はアプリ側で選ぶ。[ログ](#ログslf4j)を参照）。Servlet API は Tomcat が提供し、XML / JSON の読み込みは JDK だけで行う

## 構成

```
                ┌─ IpBlockFilter（jakarta.servlet.Filter）──────────────────────┐
  request ───▶  │  blockList.isBlocked(クライアント IP) ? 403 : 通過             │
                └───────────────────────────────┬──────────────────────────────┘
                                                │
                       IpBlockList（N 秒ごとに全ソースを読み、不変の IpMatcher に差し替え）
                                                │ 全ソースの和集合
     ┌──────────────────┬──────────────────┬────┴─────────────┬───────────────────────┐
JdbcBlockListSource  XmlBlockListSource  JsonBlockListSource  InMemoryBlockListSource  独自ソース（ラムダ可）
```

| クラス | 役割 |
|---|---|
| `IpBlockFilter` | Filter 本体。web.xml の init-param でも Java API でも設定できる |
| `IpBlockList` | ソースの集約・定期リロード・失敗時の保持。Servlet API に依存しない |
| `BlockListSource` | 取得元のインターフェース（`Collection<String> load()`） |
| `IpMatcher` | 単体 IP / CIDR（IPv4・IPv6）の高速マッチャー。不変でスレッドセーフ |
| `ForwardedForResolver` | X-Forwarded-For からクライアント IP を求める（任意）。未設定ならクライアント IP = `getRemoteAddr()` |

## 使い方 1: web.xml

```xml
<filter>
  <filter-name>ipBlockFilter</filter-name>
  <filter-class>jp.try0.servlet.ipblock.IpBlockFilter</filter-class>
  <init-param><param-name>mode</param-name><param-value>db,xml</param-value></init-param>
  <init-param><param-name>reloadIntervalSeconds</param-name><param-value>30</param-value></init-param>
  <init-param><param-name>db.jndiName</param-name><param-value>jdbc/appDb</param-value></init-param>
  <init-param><param-name>db.query</param-name><param-value>SELECT ip FROM blocked_ip WHERE enabled = TRUE</param-value></init-param>
  <init-param><param-name>xml.location</param-name><param-value>/WEB-INF/ip-block-list.xml</param-value></init-param>
</filter>
<filter-mapping>
  <filter-name>ipBlockFilter</filter-name>
  <url-pattern>/*</url-pattern>
</filter-mapping>
```

| init-param | 必須 | 内容 |
|---|---|---|
| `mode` | ○ | `db` / `xml` / `json` をカンマ区切りで指定。複数指定すると和集合になる |
| `reloadIntervalSeconds` | | リロード間隔（秒）。既定 30。`0` にすると定期リロードしない |
| `failOnStartupError` | | `true` にすると、初回ロードに失敗したときにフィルターの初期化（＝アプリの起動）を失敗させる。既定 `false` |
| `denyStatus` | | 拒否するときの HTTP ステータス（400～599）。既定 403 |
| `db.jndiName` | db 時 | `jdbc/appDb` または `java:comp/env/jdbc/appDb` |
| `db.query` | db 時 | 1 列目に IP / CIDR を返す SELECT 文 |
| `db.queryTimeoutSeconds` | | クエリのタイムアウト（秒）。既定 10 |
| `xml.location` / `json.location` | xml / json 時 | ファイルの場所（下記） |
| `forwardedFor.trustedProxies` | | 信頼するプロキシの IP / CIDR（カンマ・空白・改行区切り）。指定すると X-Forwarded-For を見る（[後述](#リバースプロキシの後ろで使う場合x-forwarded-for)） |
| `forwardedFor.header` | | 読み取るヘッダー名。既定 `X-Forwarded-For` |

location の書き方:

- `/WEB-INF/ip-block-list.xml` … Web アプリ内のパス
- `classpath:ip-block-list.json` … クラスパス上のリソース
- `file:/etc/myapp/ip-block-list.xml`、`file:C:/conf/ip-block-list.json` … ファイルシステム上のファイル（運用中に書き換えるならこれ）

DataSource は `context.xml` の `<Resource>` で定義しておく。

### XML 形式

```xml
<?xml version="1.0" encoding="UTF-8"?>
<ip-block-list>
  <ip>192.0.2.10</ip>
  <ip note="属性は自由に付けてよい（無視される）">198.51.100.0/24</ip>
  <ip>2001:db8::/32</ip>
</ip-block-list>
```

### JSON 形式

```json
{
  "ips": [
    "192.0.2.10",
    { "ip": "198.51.100.0/24", "note": "ip 以外のキーは無視される" }
  ]
}
```

ルートを配列にした `["192.0.2.10", "198.51.100.0/24"]` の形式でもよい。

JSON は外部ライブラリを使わず、同梱の小さなパーサー（`SimpleJson`）で読む。標準の JSON（RFC 8259）どおりに厳密に読むので、次の点に注意する。

- 文字コードは UTF-8（BOM 付きでもよい）
- コメント（`//`、`/* */`）、末尾のカンマ、単一引用符は書けない
- 同じキーを 2 回書くとエラーになる（`"ips"` を 2 つ書いて片方が黙って無視される、ということは起きない）
- ネストは 100 段まで

エラーになったときは何行目の何文字目かが警告ログに出て、前回読み込めた内容が使われ続ける。

### DB テーブル例

```sql
CREATE TABLE blocked_ip (
  ip       VARCHAR(64)  PRIMARY KEY,         -- 192.0.2.10 / 198.51.100.0/24 / 2001:db8::/32
  enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
  note     VARCHAR(255)
);
```

## 使い方 2: Java API

```java
IpBlockList blockList = IpBlockList.builder()
        .addSource(new JdbcBlockListSource(dataSource, "SELECT ip FROM blocked_ip WHERE enabled = TRUE"))
        .addSource(XmlBlockListSource.ofFile(Paths.get("/etc/myapp/ip-block-list.xml")))
        .addSource(JsonBlockListSource.ofClasspath("ip-block-list.json"))
        .addSource(BlockListSource.named("threat-feed", () -> client.fetchBlockedIps())) // 独自ソース
        .reloadInterval(Duration.ofSeconds(30))
        .failOnStartupError(false)
        .build();
```

### ServletContextListener で登録する場合

```java
@WebListener
public class IpBlockSetup implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent event) {
        IpBlockList blockList = IpBlockList.builder()...build();
        FilterRegistration.Dynamic reg =
                event.getServletContext().addFilter("ipBlockFilter", new IpBlockFilter(blockList));
        // true = web.xml で宣言したフィルター（RemoteIpFilter など）の後ろに入れる
        reg.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
    }
}
```

### Spring Boot の場合

```java
@Bean
public FilterRegistrationBean<IpBlockFilter> ipBlockFilter(DataSource dataSource) {
    IpBlockList blockList = IpBlockList.builder()
            .addSource(new JdbcBlockListSource(dataSource, "SELECT ip FROM blocked_ip WHERE enabled = TRUE"))
            .build();
    FilterRegistrationBean<IpBlockFilter> reg = new FilterRegistrationBean<>(new IpBlockFilter(blockList));
    reg.addUrlPatterns("/*");
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return reg;
}
```

### 実行中に IP を追加・削除する（管理画面など）

```java
InMemoryBlockListSource manual = new InMemoryBlockListSource();
IpBlockList blockList = IpBlockList.builder().addSource(dbSource).addSource(manual).build();

manual.add("203.0.113.5");    // 不正な値なら IllegalArgumentException（何も追加されない）
blockList.reloadNow();        // 次の定期リロードを待たずにすぐ反映
manual.remove("203.0.113.5"); // 表記が違っても同じアドレス・範囲なら消える（"203.0.113.5/32" でもよい）
```

`InMemoryBlockListSource` は、表記が違っても同じアドレス・範囲を 1 件として扱う（`2001:DB8::1` と `2001:db8::1` など）。`load()` は最初に登録したときの表記で返す。

web.xml で設定した場合も、`ipBlockFilter.getBlockList().reloadNow()` で即時に反映できる。

## リバースプロキシの後ろで使う場合（X-Forwarded-For）

ロードバランサーやリバースプロキシの後ろでは `getRemoteAddr()` がプロキシの IP になるので、本来のクライアント IP を X-Forwarded-For ヘッダーから取る必要がある。

X-Forwarded-For はクライアントが自由に書けるヘッダーなので、**信頼するプロキシの IP / CIDR を必ず指定する**。指定したプロキシから来たリクエストのときだけヘッダーを見る。

```xml
<init-param>
  <param-name>forwardedFor.trustedProxies</param-name>
  <param-value>10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16</param-value>
</init-param>
<!-- 任意。既定は X-Forwarded-For -->
<init-param><param-name>forwardedFor.header</param-name><param-value>X-Forwarded-For</param-value></init-param>
```

```java
IpBlockFilter filter = IpBlockFilter.builder(blockList)
        .forwardedFor(ForwardedForResolver.builder()
                .trustedProxies("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
                .build())
        .denyStatus(403)
        .build();
```

クライアント IP の決め方（Tomcat の RemoteIpFilter と同じ考え方）:

1. 接続元（`getRemoteAddr()`）が信頼するプロキシでなければ、ヘッダーは見ずに接続元を使う。
2. 信頼するプロキシからの接続なら、ヘッダーの値を**右から**順に見ていき、信頼するプロキシでない最初のアドレスをクライアント IP とする。
3. すべて信頼するプロキシならいちばん左、ヘッダーがなければ接続元を使う。

| 接続元 | X-Forwarded-For | クライアント IP | 説明 |
|---|---|---|---|
| `203.0.113.9` | `1.1.1.1` | `203.0.113.9` | プロキシを通っていないので、ヘッダーは見ない |
| `10.0.0.1` | `203.0.113.9` | `203.0.113.9` | |
| `10.0.0.1` | `1.1.1.1, 203.0.113.9` | `203.0.113.9` | `1.1.1.1` はクライアントが偽装して書いた値かもしれないので使わない |
| `10.0.0.1` | `203.0.113.9, 10.0.0.2` | `203.0.113.9` | プロキシが 2 段（`10.0.0.2` も信頼するプロキシ） |

- **ヘッダーの形式:** ヘッダーが複数行ある場合は上から順につなげる。値に付いたポート（`203.0.113.9:4711`、`[2001:db8::1]:443`）は取り除く。
- **IP 以外の値:** `unknown` などに行き当たったら、そこで止める（その値はどのブロック対象にもマッチしない）。
- **全員を信頼する指定:** `0.0.0.0/0` や `::/0` を指定すると全員を信頼することになり、ヘッダーのいちばん左が使われて偽装し放題になる。アプリにプロキシ経由でしか届かないことがネットワーク上保証されている場合以外は使わないこと。

フィルターの前に Tomcat の `RemoteIpFilter` / `RemoteIpValve` を通して `getRemoteAddr()` 自体を書き換える方法でもよい。その場合、このフィルターの X-Forwarded-For 設定は不要。

## 動作仕様

- **リロード**: 全ソースを読み、作り直した不変の `IpMatcher` を参照ごと差し替える。判定側はロックを取らない。
- **読み込みに失敗したとき**: 失敗したソースだけ、前回成功時の内容を使い続ける。DB 障害でブロックが外れることも、他のソースの更新が止まることもない。XML / JSON が壊れていたり空だったりした場合も「失敗」として扱う（編集途中のファイルでリストが空になることはない）。
- **起動時**: 初回ロードに失敗したソースは空として扱い、読めたソースだけで動き始める（`failOnStartupError=true` にすると起動自体を失敗させる）。
- **不正な値**: 警告ログを出して無視する。同じ値の警告は 1 回だけ出す。
- **定期実行の保護**: リロード中の例外はすべて捕まえるので、定期リロードが止まることはない。
- **停止**: `IpBlockFilter.destroy()` でリロード用スレッドを止める（再デプロイしてもスレッドが残らない）。
- **DNS**: 一切引かない。ホスト名は不正な値として扱う。
- **アドレス表記**: IPv6 の省略表記と非省略表記（`::1` と `0:0:0:0:0:0:0:1`）、IPv4 射影アドレス（`::ffff:192.0.2.1`）、スコープ ID（`%eth0`）は同じアドレスとして扱う。IPv4 射影アドレスを CIDR で書く場合、プレフィックス長は IPv6 のビット数（96～128）で数える（`::ffff:192.0.2.0/120` は `192.0.2.0/24` と同じ。96 未満は不正な値）。
- **リバースプロキシ**: `forwardedFor.trustedProxies` を設定するか、このフィルターより前に `RemoteIpFilter` / `RemoteIpValve` を通すこと（[上記](#リバースプロキシの後ろで使う場合x-forwarded-for)）。どちらもしないと、プロキシの IP で判定することになる。
- **ログ**: SLF4J で出す（[下記](#ログslf4j)）。ブロックしたリクエストは DEBUG レベルで出る。

## ログ（SLF4J）

ログは SLF4J（`slf4j-api` 2.x）で出す。ロガー名はクラス名（`jp.try0.servlet.ipblock.IpBlockList` など）。

| レベル | 内容 |
|---|---|
| ERROR | リロード中の予期しないエラー |
| WARN | ソースの読み込み失敗（失敗し始めた最初の 1 回だけスタックトレース付き）、不正なエントリ、停止時にスレッドが止まらなかった |
| INFO | 開始、件数の変化、読み込みの復旧 |
| DEBUG | ブロックしたリクエスト（クライアント IP と接続元） |

出力先のライブラリ（SLF4J のプロバイダー）は、このフィルターを使うアプリ側で 1 つ入れる。**何も入れないとログは一切出ない**（起動時に SLF4J が `No SLF4J providers were found` と標準エラーに出す）。

| 出力先 | アプリに追加する依存 |
|---|---|
| Logback | `ch.qos.logback:logback-classic`（1.3 以降） |
| Log4j 2 | `org.apache.logging.log4j:log4j-slf4j2-impl` |
| これまでどおり Tomcat のログ（JULI）に出す | `org.slf4j:slf4j-jdk14`（`slf4j-api` と同じバージョン） |

Spring Boot は標準で Logback が入っているので、追加は不要。

## Tomcat 9（javax.servlet）で使う場合

`IpBlockFilter` / `FilterInitParams` の `jakarta.servlet` を `javax.servlet` に置き換え、依存を `javax.servlet:javax.servlet-api:4.0.1` に変える。それ以外のクラスは Servlet API を使っていない。
Java 8 で動かす場合は、`List.copyOf` / `Set.copyOf` / `List.of` / `String.isBlank` を Java 8 の API に置き換える必要がある。

## ビルド

親 POM（[../pom.xml](../pom.xml)）配下のモジュール。依存ライブラリとプラグインのバージョンは親 POM で管理している。

```
# リポジトリのルートで
mvn verify                       # 全モジュールをビルド
mvn -pl ip-block-filter verify   # このモジュールだけ（テスト 155 件）
# → ip-block-filter/target/ip-block-filter-1.0.0.jar
```

アプリから使う場合の依存:

```xml
<dependency>
  <groupId>jp.try0.servlet</groupId>
  <artifactId>ip-block-filter</artifactId>
  <version>1.0.0</version>
</dependency>
```

ローカルの別プロジェクトから参照するなら、先にルートで `mvn install` しておく。
