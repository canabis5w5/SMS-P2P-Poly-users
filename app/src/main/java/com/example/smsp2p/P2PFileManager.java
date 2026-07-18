package com.example.smsp2p;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;
import org.webrtc.DataChannel;

public class P2PFileManager {

    private static final int CHUNK_SIZE = 16384; // 16 KB

    public interface FileTransferListener {
        void onMetadataReceived(String fileId, String fileName, long fileSize);
        void onProgress(String fileId, int progress);
        void onTransferComplete(String fileId, File file);
        void onError(String fileId, String errorReason);
    }

    // Хранилище метаданных для входящих файлов: <fileId, TransferState>
    private static class TransferState {
        String fileName;
        long fileSize;
        long bytesReceived;
        ByteArrayOutputStream outputStream;

        TransferState(String fileName, long fileSize) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.bytesReceived = 0;
            this.outputStream = new ByteArrayOutputStream();
        }
    }

    private final Map<String, TransferState> incomingTransfers = new HashMap<>();
    private final Context context;

    // Временные поля для удержания информации о файле на стороне ОТПРАВИТЕЛЯ до получения 'file_accept'
    private Uri pendingFileUri;
    private DataChannel pendingDataChannel;
    private String pendingRoomId;

    public P2PFileManager(Context context) {
        this.context = context;
    }

    /**
     * ШАГ 1 (ОТПРАВИТЕЛЬ): Сбор метаданных файла и отправка только JSON-запроса (file_meta).
     * Байты файла на этом этапе не отправляются! Они ждут согласия.
     */
    public void requestSendFile(Uri fileUri, DataChannel dataChannel, String currentRoomId, FileTransferListener listener) {
        String fileId = UUID.randomUUID().toString();
        try {
            // 1. Извлекаем имя и размер файла
            String fileName = "unknown_file";
            long fileSize = 0;
            Cursor cursor = context.getContentResolver().query(fileUri, null, null, null, null);
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) fileName = cursor.getString(nameIndex);
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex);
                }
                cursor.close();
            }

            // 2. Запоминаем контекст отправки во временные переменные
            this.pendingFileUri = fileUri;
            this.pendingDataChannel = dataChannel;
            this.pendingRoomId = currentRoomId;

            // 3. Формируем и отправляем зашифрованный JSON с типом 'file_meta'
            JSONObject metaJson = new JSONObject();
            metaJson.put("type", "file_meta");
            metaJson.put("fileId", fileId);
            metaJson.put("fileName", fileName);
            metaJson.put("fileSize", fileSize);

            byte[] encryptedMeta = MainActivity.CryptoHelper.encrypt(metaJson.toString(), currentRoomId);
            ByteBuffer metaBuffer = ByteBuffer.wrap(encryptedMeta);
            dataChannel.send(new DataChannel.Buffer(metaBuffer, false)); // false = ТЕКСТОВЫЙ ПАКЕТ

        } catch (Exception e) {
            listener.onError(fileId, "Ошибка инициализации запроса: " + e.getLocalizedMessage());
        }
    }

    /**
     * ШАГ 2 (ОТПРАВИТЕЛЬ): Запуск стриминга байтов. Метод вызывается,
     * когда от получателя прилетит текстовый пакет 'file_accept'.
     */
    public void startStreamingBytes(FileTransferListener listener) {
        if (pendingFileUri == null || pendingDataChannel == null || pendingRoomId == null) {
            listener.onError("pending", "Нет подготовленного файла для отправки");
            return;
        }

        new Thread(() -> {
            String fileId = "pending";
            try {
                InputStream inputStream = context.getContentResolver().openInputStream(pendingFileUri);
                if (inputStream == null) {
                    listener.onError(fileId, "Не удалось открыть файл для чтения");
                    return;
                }

                // Вычисляем размер для точного прогресс-бара
                long fileSize = 0;
                Cursor cursor = context.getContentResolver().query(pendingFileUri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex);
                    cursor.close();
                }

                byte[] buffer = new byte[CHUNK_SIZE];
                int bytesRead;
                long totalBytesSent = 0;

                // Побайтово стримим файл напрямую через открытый DataChannel
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    ByteBuffer byteBuffer = ByteBuffer.allocate(bytesRead);
                    byteBuffer.put(buffer, 0, bytesRead);
                    byteBuffer.flip();

                    pendingDataChannel.send(new DataChannel.Buffer(byteBuffer, true)); // true = БИНАРНЫЕ ДАННЫЕ

                    totalBytesSent += bytesRead;
                    if (fileSize > 0) {
                        int progress = (int) ((totalBytesSent * 100) / fileSize);
                        listener.onProgress(fileId, progress);
                    }
                    Thread.sleep(1); // Предотвращение переполнения буфера WebRTC
                }

                inputStream.close();

                // Очищаем временные поля после успешного завершения отправки
                pendingFileUri = null;
                pendingDataChannel = null;
                pendingRoomId = null;

                listener.onTransferComplete(fileId, null);

            } catch (Exception e) {
                listener.onError(fileId, "Ошибка передачи потока байт: " + e.getLocalizedMessage());
            }
        }).start();
    }

    /**
     * МЕТОД ОБРАБОТКИ МЕТАДАННЫХ (ПОЛУЧАТЕЛЬ): Вызывается при одобрении файла в UI,
     * подготавливает буфер в памяти для приема грядущих чанков.
     */
    public void registerIncomingFile(JSONObject metaJson, FileTransferListener listener) {
        try {
            String fileId = metaJson.getString("fileId");
            String fileName = metaJson.getString("fileName");
            long fileSize = metaJson.getLong("fileSize");

            incomingTransfers.put(fileId, new TransferState(fileName, fileSize));
            listener.onMetadataReceived(fileId, fileName, fileSize);
        } catch (Exception e) {
            listener.onError("unknown", "Ошибка регистрации файла: " + e.getLocalizedMessage());
        }
    }

    /**
     * МЕТОД ПРИЕМА БАЙТОВ (ПОЛУЧАТЕЛЬ): Ловит бинарные куски и склеивает их.
     * При достижении финального размера автоматически сохраняет в папку «Загрузки».
     */
    public void onChunkReceived(DataChannel.Buffer buffer, FileTransferListener listener) {
        if (incomingTransfers.isEmpty()) return;

        String activeFileId = incomingTransfers.keySet().iterator().next();
        TransferState state = incomingTransfers.get(activeFileId);
        if (state == null) return;

        try {
            ByteBuffer data = buffer.data;
            int bytesToRead = data.remaining();
            byte[] bytes = new byte[bytesToRead];
            data.get(bytes);

            state.outputStream.write(bytes);
            state.bytesReceived += bytesToRead;

            if (state.fileSize > 0) {
                int progress = (int) ((state.bytesReceived * 100) / state.fileSize);
                listener.onProgress(activeFileId, progress);
            }

            // Проверка завершения скачивания
            if (state.bytesReceived >= state.fileSize) {
                incomingTransfers.remove(activeFileId);

                File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                File outputFile = new File(downloadsDir, state.fileName);

                FileOutputStream fileOutputStream = new FileOutputStream(outputFile);
                state.outputStream.writeTo(fileOutputStream);
                fileOutputStream.close();
                state.outputStream.close();

                android.media.MediaScannerConnection.scanFile(context,
                        new String[]{outputFile.getAbsolutePath()}, null, null);

                listener.onTransferComplete(activeFileId, outputFile);
            }
        } catch (Exception e) {
            listener.onError(activeFileId, "Ошибка сборки байт: " + e.getLocalizedMessage());
        }
    }
}
