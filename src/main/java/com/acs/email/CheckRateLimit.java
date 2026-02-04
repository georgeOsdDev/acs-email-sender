package com.acs.email;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.RetryPolicy;
import com.azure.core.http.policy.RetryStrategy;
import com.azure.core.util.polling.SyncPoller;

import java.time.Duration;

/**
 * ACS メール送信レート制限検証アプリケーション
 *
 * 30/min の送信制限に達して429エラーが発生することを確認するためのアプリ
 * 35件のメール送信APIを呼び出し、429エラーがどこで発生するかを確認する
 *
 * 参考: https://learn.microsoft.com/ja-jp/azure/communication-services/quickstarts/email/send-email-advanced/throw-exception-when-tier-limit-reached
 */
public class CheckRateLimit {

    private static final String CONNECTION_STRING = System.getenv("ACS_CONNECTION_STRING");
    private static final String SENDER_ADDRESS = System.getenv("ACS_SENDER_ADDRESS");
    private static final int TOTAL_EMAILS = 35;
    private static final int HTTP_STATUS_TOO_MANY_REQUESTS = 429;

    /**
     * カスタムリトライ戦略 - 429エラーで即座に例外をスローする
     */
    public static class NoRetryOn429Strategy implements RetryStrategy {

        @Override
        public int getMaxRetries() {
            return 0; // リトライなし
        }

        @Override
        public Duration calculateRetryDelay(int retryAttempts) {
            return Duration.ZERO;
        }

        @Override
        public boolean shouldRetry(HttpResponse httpResponse) {
            int code = httpResponse.getStatusCode();

            if (code == HTTP_STATUS_TOO_MANY_REQUESTS) {
                System.err.println("\n!!! 429 Too Many Requests を受信しました !!!");
                System.err.println("レスポンスヘッダー:");
                httpResponse.getHeaders().forEach(header ->
                    System.err.println("  " + header.getName() + ": " + header.getValue())
                );
                throw new RuntimeException("429 Too Many Requests - レート制限に達しました: " + httpResponse.toString());
            }
            return false; // リトライしない
        }
    }

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("ACS メール送信 レート制限検証ツール");
        System.out.println("=================================================");
        System.out.println("目的: 30/min の送信制限で 429 エラーを再現する");
        System.out.println("送信予定件数: " + TOTAL_EMAILS + " 件");
        System.out.println("=================================================\n");

        // 設定の確認
        if (CONNECTION_STRING == null || CONNECTION_STRING.isEmpty()) {
            System.err.println("エラー: 環境変数 ACS_CONNECTION_STRING が設定されていません。");
            return;
        }

        if (SENDER_ADDRESS == null || SENDER_ADDRESS.isEmpty()) {
            System.err.println("エラー: 環境変数 ACS_SENDER_ADDRESS が設定されていません。");
            return;
        }

        // 送信先アドレスをコマンドライン引数から取得
        String recipientAddress;
        if (args.length > 0) {
            recipientAddress = args[0];
        } else {
            System.err.println("使用法: java CheckRateLimit <送信先メールアドレス>");
            System.err.println("例: java CheckRateLimit test@example.com");
            return;
        }

        System.out.println("送信元: " + SENDER_ADDRESS);
        System.out.println("送信先: " + recipientAddress);
        System.out.println("-------------------------------------------------\n");

        // 429でリトライしないEmailClientを作成
        EmailClient emailClient = new EmailClientBuilder()
            .connectionString(CONNECTION_STRING)
            .retryPolicy(new RetryPolicy(new NoRetryOn429Strategy()))
            .buildClient();

        int successCount = 0;
        int failCount = 0;

        long startTime = System.currentTimeMillis();

        for (int i = 1; i <= TOTAL_EMAILS; i++) {
            System.out.println("-------------------------------------------------");
            System.out.println("[" + i + "/" + TOTAL_EMAILS + "] メール送信を開始...");

            long emailStartTime = System.currentTimeMillis();

            try {
                // EmailMessage を構築
                EmailMessage message = new EmailMessage()
                    .setSenderAddress(SENDER_ADDRESS)
                    .setToRecipients(recipientAddress)
                    .setSubject("レート制限テスト #" + i + " / " + TOTAL_EMAILS)
                    .setBodyPlainText("これはレート制限テストメール #" + i + " です。\n送信時刻: " + java.time.LocalDateTime.now());

                // メール送信を開始（ポーリングなし、beginSendのみ）
                SyncPoller<EmailSendResult, EmailSendResult> poller = emailClient.beginSend(message);

                // 最初のレスポンスのみ取得（ポーリングはしない）
                EmailSendResult initialResult = poller.poll().getValue();

                long emailEndTime = System.currentTimeMillis();
                long duration = emailEndTime - emailStartTime;

                System.out.println("  ステータス: " + initialResult.getStatus());
                System.out.println("  オペレーションID: " + initialResult.getId());
                System.out.println("  処理時間: " + duration + " ms");

                successCount++;
                System.out.println("  結果: 送信リクエスト成功");

            } catch (RuntimeException e) {
                long emailEndTime = System.currentTimeMillis();
                long duration = emailEndTime - emailStartTime;

                failCount++;
                System.err.println("  結果: 送信リクエスト失敗");
                System.err.println("  処理時間: " + duration + " ms");
                System.err.println("  エラー: " + e.getMessage());

                if (e.getMessage() != null && e.getMessage().contains("429")) {
                    System.err.println("\n=================================================");
                    System.err.println("★★★ 429 エラーが発生しました ★★★");
                    System.err.println("発生した送信番号: " + i + " 件目");
                    System.err.println("成功した送信数: " + successCount + " 件");
                    System.err.println("=================================================");
                    break;
                }
            } catch (Exception e) {
                long emailEndTime = System.currentTimeMillis();
                long duration = emailEndTime - emailStartTime;

                failCount++;
                System.err.println("  結果: 予期しないエラー");
                System.err.println("  処理時間: " + duration + " ms");
                System.err.println("  エラー種別: " + e.getClass().getName());
                System.err.println("  エラー: " + e.getMessage());
                e.printStackTrace();
            }
        }

        long endTime = System.currentTimeMillis();
        long totalDuration = endTime - startTime;

        System.out.println("\n=================================================");
        System.out.println("テスト完了サマリー");
        System.out.println("=================================================");
        System.out.println("成功: " + successCount + " 件");
        System.out.println("失敗: " + failCount + " 件");
        System.out.println("合計処理時間: " + totalDuration + " ms (" + (totalDuration / 1000.0) + " 秒)");
        System.out.println("=================================================");
    }
}
