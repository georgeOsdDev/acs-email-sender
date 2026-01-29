package com.acs.email;

import com.azure.communication.email.EmailAsyncClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.communication.email.models.EmailSendStatus;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollerFlux;

import java.time.Duration;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ACS メール送信コンソールアプリケーション（非同期版）
 * EmailAsyncClient と PollerFlux を使用した非同期メール送信
 *
 * EmailSendStatus の値:
 * - NOT_STARTED: 現時点ではサービスから返されない
 * - RUNNING (IN_PROGRESS): メール送信操作が進行中
 * - SUCCEEDED (SUCCESSFULLY_COMPLETED): メール送信が成功（エラーなし）
 * - FAILED: メール送信が失敗（error オブジェクトに詳細あり）
 * - CANCELED: 操作がキャンセルされた
 */
public class AsyncApp {

    // 環境変数または直接設定
    private static final String CONNECTION_STRING = System.getenv("ACS_CONNECTION_STRING");
    private static final String SENDER_ADDRESS = System.getenv("ACS_SENDER_ADDRESS");

    // メインスレッドを待機させる時間
    private static final Duration MAIN_THREAD_WAIT_TIME = Duration.ofSeconds(60);

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("ACS メール送信テストアプリケーション（非同期版）");
        System.out.println("EmailAsyncClient + PollerFlux 使用");
        System.out.println("=================================================\n");

        // 設定の確認
        if (CONNECTION_STRING == null || CONNECTION_STRING.isEmpty()) {
            System.err.println("エラー: 環境変数 ACS_CONNECTION_STRING が設定されていません。");
            System.err.println("設定例: export ACS_CONNECTION_STRING=\"endpoint=https://<resource-name>.communication.azure.com/;accesskey=<access-key>\"");
            return;
        }

        if (SENDER_ADDRESS == null || SENDER_ADDRESS.isEmpty()) {
            System.err.println("エラー: 環境変数 ACS_SENDER_ADDRESS が設定されていません。");
            System.err.println("設定例: export ACS_SENDER_ADDRESS=\"DoNotReply@xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.azurecomm.net\"");
            return;
        }

        Scanner scanner = new Scanner(System.in);

        System.out.print("送信先メールアドレスを入力してください: ");
        String recipientAddress = scanner.nextLine().trim();

        if (recipientAddress.isEmpty()) {
            System.err.println("エラー: 送信先メールアドレスが入力されていません。");
            scanner.close();
            return;
        }

        System.out.print("メールの件名を入力してください（デフォルト: ACS Email Test）: ");
        String subject = scanner.nextLine().trim();
        if (subject.isEmpty()) {
            subject = "ACS Email Test (Async)";
        }

        System.out.print("メール本文を入力してください（デフォルト: This is a test email.）: ");
        String body = scanner.nextLine().trim();
        if (body.isEmpty()) {
            body = "This is a test email from Azure Communication Services using AsyncClient.";
        }

        scanner.close();

        // メール送信を実行
        sendEmailAsync(recipientAddress, subject, body);
    }

    /**
     * 非同期でメールを送信し、EmailSendResult のステータスを詳細に出力する
     */
    private static void sendEmailAsync(String recipientAddress, String subject, String body) {
        System.out.println("\n-------------------------------------------------");
        System.out.println("メール送信を開始します（非同期）...");
        System.out.println("-------------------------------------------------");
        System.out.println("送信元: " + SENDER_ADDRESS);
        System.out.println("送信先: " + recipientAddress);
        System.out.println("件名: " + subject);
        System.out.println("-------------------------------------------------\n");

        // 完了を待つためのラッチ
        CountDownLatch latch = new CountDownLatch(1);

        // ExecutorService でポーリングを別スレッドで実行
        ExecutorService executorService = Executors.newSingleThreadExecutor();

        try {
            // EmailAsyncClient を作成
            EmailAsyncClient emailAsyncClient = new EmailClientBuilder()
                .connectionString(CONNECTION_STRING)
                .buildAsyncClient();

            // EmailMessage を構築
            EmailMessage emailMessage = new EmailMessage()
                .setSenderAddress(SENDER_ADDRESS)
                .setToRecipients(recipientAddress)
                .setSubject(subject)
                .setBodyPlainText(body)
                .setBodyHtml("<html><body><h1>" + subject + "</h1><p>" + body + "</p></body></html>");

            System.out.println("メール送信リクエストを開始（非同期）...\n");

            // 非同期送信操作を開始
            PollerFlux<EmailSendResult, EmailSendResult> poller = emailAsyncClient.beginSend(emailMessage);

            // ポーリング回数のカウンター
            final int[] pollCount = {0};

            executorService.submit(() -> {
                // PollerFlux を購読して状態を監視
                poller.subscribe(
                    response -> {
                        // 各ポーリング応答を処理
                        pollCount[0]++;
                        System.out.println("[ポーリング #" + pollCount[0] + "] (非同期)");
                        System.out.println("  LongRunningOperationStatus: " + response.getStatus());

                        EmailSendResult result = response.getValue();
                        if (result != null) {
                            System.out.println("  EmailSendResult.id: " + result.getId());
                            System.out.println("  EmailSendResult.status: " + result.getStatus());

                            // エラー情報があれば出力
                            if (result.getError() != null) {
                                System.out.println("  EmailSendResult.error.code: " + result.getError().getCode());
                                System.out.println("  EmailSendResult.error.message: " + result.getError().getMessage());
                            }
                        }

                        // 完了したかチェック
                        if (response.getStatus() == LongRunningOperationStatus.SUCCESSFULLY_COMPLETED) {
                            System.out.println("\n=================================================");
                            System.out.println("【最終結果】成功");
                            System.out.println("=================================================");
                            printEmailSendResultDetails(result);
                            latch.countDown();
                        } else if (response.getStatus() == LongRunningOperationStatus.FAILED) {
                            System.out.println("\n=================================================");
                            System.out.println("【最終結果】失敗");
                            System.out.println("=================================================");
                            printEmailSendResultDetails(result);
                            latch.countDown();
                        } else {
                            System.out.println("  （処理中...）\n");
                        }
                    },
                    error -> {
                        // エラー発生時の処理
                        System.err.println("\n【エラー発生】");
                        System.err.println("エラークラス: " + error.getClass().getName());
                        System.err.println("メッセージ: " + error.getMessage());
                        error.printStackTrace();
                        latch.countDown();
                    },
                    () -> {
                        // 完了時の処理
                        System.out.println("\n非同期処理が完了しました。");
                        latch.countDown();
                    }
                );
            });

            // メインスレッドを待機させる
            System.out.println("メインスレッドは非同期処理の完了を待機中...\n");
            boolean completed = latch.await(MAIN_THREAD_WAIT_TIME.toMillis(), TimeUnit.MILLISECONDS);

            if (!completed) {
                System.err.println("警告: タイムアウトしました（" + MAIN_THREAD_WAIT_TIME.toSeconds() + "秒）");
            }

        } catch (Exception e) {
            System.err.println("\n【例外発生】");
            System.err.println("例外クラス: " + e.getClass().getName());
            System.err.println("メッセージ: " + e.getMessage());
            System.err.println("\nスタックトレース:");
            e.printStackTrace();
        } finally {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
            System.out.println("\nメインスレッド終了。");
        }
    }

    /**
     * EmailSendResult の詳細を出力
     */
    private static void printEmailSendResultDetails(EmailSendResult result) {
        if (result == null) {
            System.out.println("EmailSendResult: null");
            return;
        }

        System.out.println("\n【EmailSendResult 詳細】");
        System.out.println("-------------------------------------------------");
        System.out.println("Operation ID: " + result.getId());
        System.out.println("Status: " + result.getStatus());

        // EmailSendStatus の説明を出力
        EmailSendStatus status = result.getStatus();
        System.out.println("\n【EmailSendStatus の解説】");
        if (status == EmailSendStatus.NOT_STARTED) {
            System.out.println("  NOT_STARTED: 操作がまだ開始されていません");
            System.out.println("  ※ 現時点ではサービスから返されないステータスです");
        } else if (status == EmailSendStatus.RUNNING) {
            System.out.println("  RUNNING (IN_PROGRESS): メール送信操作が進行中です");
        } else if (status == EmailSendStatus.SUCCEEDED) {
            System.out.println("  SUCCEEDED (SUCCESSFULLY_COMPLETED): メール送信が成功しました");
            System.out.println("  メールは配信のために送信されました。");
            System.out.println("  詳細な配信ステータスは Azure Monitor または Event Grid で確認できます。");
        } else if (status == EmailSendStatus.FAILED) {
            System.out.println("  FAILED: メール送信が失敗しました");
        } else if (status == EmailSendStatus.CANCELED) {
            System.out.println("  CANCELED: メール送信がキャンセルされました");
        }

        // エラー情報の詳細
        if (result.getError() != null) {
            System.out.println("\n【エラー詳細】");
            System.out.println("-------------------------------------------------");
            System.out.println("Error Code: " + result.getError().getCode());
            System.out.println("Error Message: " + result.getError().getMessage());
        }

        System.out.println("\n=================================================");
        System.out.println("検証完了");
        System.out.println("=================================================");
    }
}
