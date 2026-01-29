# ACS メール送信テストアプリケーション

Azure Communication Services (ACS) のメール送信機能を使用して、`EmailSendResult` のステータスパターンを検証するためのJavaコンソールアプリケーションです。

## ステータスの種類

ACS Email SDK では、2種類のステータスが存在します。これらは別々の enum であり、混同しないように注意が必要です。

### 1. LongRunningOperationStatus（Azure SDK 共通）

`PollResponse.getStatus()` で取得できる、Azure SDK の長時間実行操作（LRO）の汎用ステータスです。

| ステータス | 説明 |
|-----------|------|
| `NOT_STARTED` | 操作がまだ開始されていない |
| `IN_PROGRESS` | 操作が進行中（ポーリング継続） |
| `SUCCESSFULLY_COMPLETED` | 操作が正常に完了（終端状態） |
| `FAILED` | 操作が失敗（終端状態） |
| `USER_CANCELLED` | ユーザーによりキャンセル（終端状態） |

### 2. EmailSendStatus（ACS Email 固有）

`EmailSendResult.getStatus()` で取得できる、ACS Email サービス固有のステータスです。

| ステータス | 説明 |
|-----------|------|
| `NotStarted` | 操作がまだ開始されていない（※現時点ではサービスから返されない） |
| `Running` | メール送信操作が進行中 |
| `Succeeded` | メール送信が成功（ACSがメールを送信キューに投入完了） |
| `Failed` | メール送信操作が失敗（`error` オブジェクトに詳細あり） |
| `Canceled` | 操作がキャンセルされた |

### ステータスの対応関係

| LongRunningOperationStatus | EmailSendStatus | 説明 |
|---------------------------|-----------------|------|
| `IN_PROGRESS` | `Running` | 処理中 |
| `SUCCESSFULLY_COMPLETED` | `Succeeded` | 成功 |
| `FAILED` | `Failed` | 失敗 |

## EmailSendResult の構造

```java
public class EmailSendResult {
    // 操作の一意のID (UUID)
    private final String id;

    // 操作のステータス (EmailSendStatus)
    private final EmailSendStatus status;

    // 失敗時のエラー詳細（該当する場合）
    private ResponseError error;
}
```

## 重要: Succeeded の意味

**`EmailSendStatus.Succeeded` は「メールが受信者に届いた」ことを意味しません。**

| ステータス | 意味 |
|-----------|------|
| `Succeeded` | ACS がメールを**送信キューに正常に投入した** |
| ≠ | 受信者のメールサーバーに到達した |
| ≠ | 受信者がメールを受信した |

### EmailSendResult で検知できる/できないエラー

| エラー種別 | 検知可能 | 例 |
|-----------|:---:|-----|
| 送信元アドレスが無効 | ✅ | 検証されていないドメイン |
| 接続文字列が無効 | ✅ | 認証エラー |
| メッセージフォーマット不正 | ✅ | 必須フィールド欠落 |
| サプレッションリスト登録済み | ✅ | `EmailDroppedAllRecipientsSuppressed` |
| **存在しないメールアドレス** | ❌ | `user@valid-domain.com` |
| **存在しないドメイン** | ❌ | `@microsoft.comm`（Succeeded になる） |
| **受信者メールボックス満杯** | ❌ | バウンスメール |
| **スパムフィルタでブロック** | ❌ | 受信者側の拒否 |

### 配信結果を確認する方法

実際の配信結果を確認するには：

1. **Azure Event Grid** で `EmailDeliveryReportReceived` イベントを購読
2. **Azure Monitor** でログを確認

## 前提条件

1. **Java Development Kit (JDK)** 11以上
2. **Apache Maven** 3.x
3. **Azure Communication Services リソース** と接続文字列
4. **メール通信サービスリソース** と検証済みドメイン

## セットアップ

### 1. 環境変数の設定

プロジェクトルートに `.env` ファイルを作成し、以下の環境変数を設定してください：

```bash
# .env ファイル
ACS_CONNECTION_STRING="endpoint=https://<resource-name>.communication.azure.com/;accesskey=<access-key>"
ACS_SENDER_ADDRESS="DoNotReply@xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.azurecomm.net"
```

実行前に `.env` ファイルを読み込みます：

```bash
# .env ファイルを読み込む
source .env
export ACS_CONNECTION_STRING ACS_SENDER_ADDRESS
```

### 2. ビルド

```bash
cd acs-email-sender
mvn clean compile
```

### 3. 実行

```bash
mvn exec:java -Dexec.mainClass="com.acs.email.App" -Dexec.cleanupDaemonThreads="false"
```

または、パッケージ化して実行：

```bash
mvn package
java -jar target/acs-email-sender-1.0-SNAPSHOT.jar
```

## 出力例

### 成功時の出力

```
=================================================
ACS メール送信テストアプリケーション
EmailSendResult ステータスパターン検証ツール
=================================================

送信先メールアドレスを入力してください: user@example.com

-------------------------------------------------
メール送信を開始します...
-------------------------------------------------
送信元: DoNotReply@xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.azurecomm.net
送信先: user@example.com
件名: ACS Email Test
-------------------------------------------------

メール送信リクエストを開始...

[ポーリング #1]
  LongRunningOperationStatus: IN_PROGRESS
  isComplete: false
  EmailSendResult.id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
  EmailSendResult.status: Running

=================================================
【最終結果】
=================================================
  LongRunningOperationStatus: SUCCESSFULLY_COMPLETED
  isComplete: true
  EmailSendResult.id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
  EmailSendResult.status: Succeeded

【EmailSendResult 詳細】
-------------------------------------------------
Operation ID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
Status: Succeeded

【EmailSendStatus の解説】
  SUCCEEDED (SUCCESSFULLY_COMPLETED): メール送信が成功しました
  メールは配信のために送信されました。
  詳細な配信ステータスは Azure Monitor または Event Grid で確認できます。

=================================================
検証完了
=================================================
```

### 失敗時の出力例

```
【最終結果】
=================================================
  LongRunningOperationStatus: FAILED
  isComplete: true
  EmailSendResult.id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
  EmailSendResult.status: Failed
  EmailSendResult.error.code: InvalidEmailAddress
  EmailSendResult.error.message: The email address 'invalid-email' is not valid.

【エラー詳細】
-------------------------------------------------
Error Code: InvalidEmailAddress
Error Message: The email address 'invalid-email' is not valid.
```

## トラブルシューティング

### メール配信の確認
- 成功ステータスは、メールが正常に送信されたことのみを示します
- 受信者側での配信ステータスは [Email イベントの処理方法](https://learn.microsoft.com/azure/communication-services/quickstarts/email/handle-email-events) を参照してください

### スロットリング
- アプリケーションがハングする場合は、メール送信がスロットリングされている可能性があります
- [ティア制限の処理方法](https://learn.microsoft.com/azure/communication-services/quickstarts/email/send-email-advanced/throw-exception-when-tier-limit-reached) を参照してください

## 参考リンク

- [Azure Communication Services Email SDK for Java](https://github.com/Azure/azure-sdk-for-java/tree/main/sdk/communication/azure-communication-email)
- [Send email quickstart](https://learn.microsoft.com/azure/communication-services/quickstarts/email/send-email)
- [Email concepts](https://learn.microsoft.com/azure/communication-services/concepts/email/email-overview)
