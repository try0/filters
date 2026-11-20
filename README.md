# filters

Servlet Filter をまとめた Maven マルチモジュールプロジェクト。

- 対象: Tomcat 10.1 以降（`jakarta.servlet`）、Java 11 以降
- ビルド: Maven 3.9 以降

## モジュール

| モジュール | 内容 |
|---|---|
| [ip-block-filter](ip-block-filter/) | DB / XML / JSON / Java API から読み込んだ IP（単体・CIDR）を定期リロードしてブロックする |

## 構成

```
filters/
├── LICENSE              MIT License
├── pom.xml              親 POM（packaging=pom）。依存・プラグインのバージョン、ライセンス・SCM などの情報を一元管理
└── ip-block-filter/
    ├── pom.xml          親 POM を継承。バージョンは書かない
    └── src/
```

## ビルド

```
mvn verify    # 全モジュールのビルドとテスト
mvn install   # ローカルリポジトリへインストール（他プロジェクトから参照する場合）
```

## リリース（Maven Central）

`release` プロファイルで、sources / javadoc の jar を作り、GPG で署名して Central Portal に送る。

```
mvn -P release deploy
```

- 事前に `~/.m2/settings.xml` に `<server><id>central</id>` として Central Portal のユーザートークンを登録し、GPG の鍵を用意しておく
- 送ったあとは Central Portal の Deployments 画面で内容を確認し、Publish する（自動では公開しない）
- 親 POM も一緒に公開される（子モジュールが親 POM を参照するため）

## CI（GitHub Actions）

[.github/workflows/test.yml](.github/workflows/test.yml) で、全モジュールのビルドとユニットテスト（`mvn -B -ntp verify`）を実行する。

- 実行タイミング: `main` / `master` への push、プルリクエスト、Actions 画面からの手動実行
- JDK: 11（対応する最低バージョン）、17、21、25 の 4 つで並列に実行（Temurin）
- 依存ライブラリは `~/.m2` をキャッシュして再利用する
- テストが失敗したら、その JDK の `surefire-reports` を 7 日間アーティファクトとして残す

## フィルターを追加するとき

1. `filters/<モジュール名>/` を作り、`pom.xml` で親 POM を `<parent>` に指定する
2. 親 POM の `<modules>` に `<module><モジュール名></module>` を追加する
3. 使うライブラリのバージョンは親 POM の `<dependencyManagement>` に書き、モジュール側ではバージョンを省略する
4. `<name>` と `<description>` はモジュールの `pom.xml` に書く（Maven Central で必須）。ライセンス・開発者・SCM は親 POM から継承される

## ライセンス

[MIT License](LICENSE)
