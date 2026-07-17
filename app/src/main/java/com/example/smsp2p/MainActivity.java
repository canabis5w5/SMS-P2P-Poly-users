package com.example.smsp2p;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import org.webrtc.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends AppCompatActivity {

    private EditText etSdpExchange, etMessage, etStunServer;
    private TextView tvStatus, tvChatHistory;
    private Button btnCreateOffer, btnAcceptSdp, btnSend, btnOpenSettings;

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;

    // Переменные авто-обмена
    private WebSocket webSocket;
    private OkHttpClient client;

    private boolean isInitiator = false;
    private String currentRoomId = "";
    private SharedPreferences prefs;
    private String wsServerUrl = "";

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("SMSP2P_PREFS", Context.MODE_PRIVATE);
        wsServerUrl = prefs.getString("ws_url", "ws://ipcamssss.online:8888");

        etSdpExchange = findViewById(R.id.etSdpExchange);
        etMessage = findViewById(R.id.etMessage);

        tvStatus = findViewById(R.id.tvStatus);
        tvChatHistory = findViewById(R.id.tvChatHistory);
        btnCreateOffer = findViewById(R.id.btnCreateOffer);
        btnAcceptSdp = findViewById(R.id.btnAcceptSdp);
        btnSend = findViewById(R.id.btnSend);
        Button btnOpenSettings = findViewById(R.id.btnOpenSettings);

        // Настройка названий кнопок для динамических комнат
        btnCreateOffer.setText("1. Создать новую комнату");
        btnAcceptSdp.setText("2. Войти по коду друга");
        etSdpExchange.setHint("Сюда вводите 6-значный код комнаты...");

        // Инициализация WebRTC ядра
        PeerConnectionFactory.InitializationOptions initOptions = PeerConnectionFactory.InitializationOptions
                .builder(getApplicationContext()).createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory();


        btnCreateOffer.setOnClickListener(v -> actionCreateNewRoom());
        btnAcceptSdp.setOnClickListener(v -> actionJoinRoomByCode());
        btnSend.setOnClickListener(v -> sendMessage());

        // КНОПКА НАСТРОЕК
        btnOpenSettings.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(this, SettingsActivity.class);
            startActivity(intent);
        });

        // ОЧИСТКИ ПОЛЯ SDP:
        etSdpExchange.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                // Проверяем клик по правой иконке-крестику
                if (event.getRawX() >= (etSdpExchange.getRight() - etSdpExchange.getCompoundDrawables()[2].getBounds().width())) {
                    etSdpExchange.setText(""); // Мгновенно очищаем длинный SDP код
                    updateStatus("Поле обмена кодами очищено.");
                    return true;
                }
            }
            return false;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Перечитываем актуальные настройки из памяти после возвращения с экрана настроек
        if (prefs != null) {
            wsServerUrl = prefs.getString("ws_url", "ws://ipcamssss.online:8888");
        }

        // Закрываем старый сокет, если он случайно остался открытым
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Переподключение");
            } catch (Exception e) {
                Log.e("WebRTC_WS", "Ошибка закрытия старого сокета", e);
            }
        }

        // Запускаем подключение с новым, исправленным адресом сервера
        initWebSocket();
    }

    private void initWebSocket() {
        // Проверка 1: Строка не должна быть пустой
        if (wsServerUrl == null || wsServerUrl.trim().isEmpty()) {
            updateStatus("Ошибка: адрес сервера пуст. Задайте его в Настройках.");
            return;
        }

        // Проверка 2: Безопасный разбор URL. Если в строке осталась только схема (например, "ws://"), блокируем запуск
        String checkUrl = wsServerUrl.replace("ws://", "").replace("wss://", "").trim();
        if (checkUrl.isEmpty()) {
            updateStatus("Ошибка: введите адрес хоста в Настройках.");
            return;
        }

        client = new OkHttpClient();

        try{
        Request request = new Request.Builder().url(wsServerUrl).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                updateStatus("Подключено к серверу обмена!");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    org.json.JSONObject json = new org.json.JSONObject(text);
                    String msgType = json.optString("type");

                    if ("room_status".equals(msgType)) {
                        int count = json.getInt("count");
                        runOnUiThread(() -> {
                            if (count < 2) {
                                updateStatus("Комната " + currentRoomId + ": Ожидание друга...");
                            } else {
                                updateStatus("Друг на связи! Подключаемся автоматически...");

                                // АВТОМАТИЗАЦИЯ: Если мы создатель комнаты, запускаем WebRTC без кликов.
                                // Делаем микро-поток с задержкой в 300мс, чтобы WebRTC успел проинициализироваться.
                                if (isInitiator) {
                                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                        triggerWebRtcOffer();
                                    }, 300);
                                }
                            }
                        });
                        return;
                    }

                    if ("sdp".equals(msgType)) {
                        String remoteSdpText = json.getString("sdp");
                        SessionDescription localDesc = peerConnection.getLocalDescription();
                        if (localDesc != null && sanitizeSdp(remoteSdpText).equals(sanitizeSdp(localDesc.description))) {
                            return;
                        }

                        // Автоматически принимаем прилетевший по сети код
                        runOnUiThread(() -> {
                            etSdpExchange.setText(remoteSdpText);
                            acceptSdpFromEditText();
                        });
                    }
                } catch (Exception e) {
                    Log.e("WebRTC_WS", "Ошибка авто-обмена", e);
                }
            }
        });
    } catch (IllegalArgumentException e) {
        // ИСПРАВЛЕНО: Если OkHttp выкинет ошибку хоста, приложение НЕ упадет, а мягко выведет статус
        Log.e("WebRTC_WS", "Критическая ошибка разбора URL: " + wsServerUrl, e);
        updateStatus("Неверный адрес сервера! Измените в Настройках.");
    }
    }

    // Метод для динамического создания PeerConnection в зависимости от текста в etStunServer
    private void initPeerConnection() {
        if (peerConnection != null) {
            return; // Если соединение уже инициализировано, пропускаем
        }

        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        String stunUrl = prefs.getString("stun_url", "stun:ipcamssss.online:3478");
        //String stunUrl = etStunServer.getText().toString().trim();

        // Если поле не пустое, динамически подключаем указанный STUN-сервер
        if (!stunUrl.isEmpty()) {
            if (!stunUrl.startsWith("stun:")) {
                stunUrl = "stun:" + stunUrl;
            }
            iceServers.add(PeerConnection.IceServer.builder(stunUrl).createIceServer());
            updateStatus("Используем STUN: " + stunUrl);
        } else {
            updateStatus("Локальный режим (без STUN-сервера)");
        }

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        // Ваши проверенные оптимизации скорости сбора
        rtcConfig.disableIPv6OnWifi = true;
        rtcConfig.iceCandidatePoolSize = 0;
        rtcConfig.iceConnectionReceivingTimeout = 1000;

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                runOnUiThread(() -> {
                    SessionDescription localDescription = peerConnection.getLocalDescription();
                    if (localDescription != null) {
                        etSdpExchange.setText(localDescription.description);
                    }
                });
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
                if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                    runOnUiThread(() -> {
                        SessionDescription localDescription = peerConnection.getLocalDescription();
                        if (localDescription != null) {
                            etSdpExchange.setText(localDescription.description);
                            updateStatus("Код готов! Отправляем...");
                            new Thread(() -> sendSdpToNetwork(localDescription.description)).start();
                        }
                    });
                }
            }

            @Override
            public void onDataChannel(DataChannel channel) {
                dataChannel = channel;
                setupDataChannelCallbacks();
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                runOnUiThread(() -> {
                    if (state == PeerConnection.IceConnectionState.CONNECTED) {
                        updateStatus("Соединено напрямую через Интернет!");
                    } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        updateStatus("Связь разорвана.");
                    }
                });
            }

            @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            @Override public void onAddStream(MediaStream stream) {}
            @Override public void onRemoveStream(MediaStream stream) {}
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {}
            @Override public void onRenegotiationNeeded() {}
        });
    }

    private void actionCreateNewRoom() {
        initPeerConnection(); // Принудительно создаем PeerConnection с текущим STUN
        isInitiator = true; // Фиксируем, что мы создатель
        int code = new Random().nextInt(900000) + 100000;
        currentRoomId = String.valueOf(code);
        etSdpExchange.setText("ВАШ КОД: " + currentRoomId);
        updateStatus("Создана комната " + currentRoomId + ". Передайте код другу.");

        String joinMsg = "{\"type\":\"join\",\"room\":\"" + currentRoomId + "\"}";
        if (webSocket != null) webSocket.send(joinMsg);
    }


    private void actionJoinRoomByCode() {
        String enteredCode = etSdpExchange.getText().toString().trim();
        if (enteredCode.isEmpty() || enteredCode.length() < 6 || enteredCode.contains("ВАШ КОД")) {
            updateStatus("Введите корректный 6-значный код!");
            return;
        }

        initPeerConnection(); // Принудительно создаем PeerConnection со вторым STUN перед входом

        isInitiator = false; // Мы входим по коду, значит мы не создатель
        currentRoomId = enteredCode;
        updateStatus("Вход в комнату " + currentRoomId + "...");

        String joinMsg = "{\"type\":\"join\",\"room\":\"" + currentRoomId + "\"}";
        if (webSocket != null) webSocket.send(joinMsg);
    }

    private void triggerWebRtcOffer() {
        if (peerConnection == null) return;
        updateStatus("Генерация кода соединения (STUN)...");

        DataChannel.Init init = new DataChannel.Init();
        dataChannel = peerConnection.createDataChannel("sendDataChannel", init);
        setupDataChannelCallbacks();

        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        peerConnection.createOffer(new SdpObserverAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription desc) {
                peerConnection.setLocalDescription(new SdpObserverAdapter() {
                    @Override
                    public void onSetSuccess() {
                        // ИСПРАВЛЕНО: Отправляем сразу! Соединение начнется в ту же секунду
                        runOnUiThread(() -> updateStatus("Оффер отправлен в комнату."));
                        new Thread(() -> sendSdpToNetwork(desc.description)).start();
                    }
                }, desc);
            }
        }, constraints);
    }


    private void acceptSdpFromEditText() {
        if (peerConnection == null) initPeerConnection();

        String remoteSdpText = etSdpExchange.getText().toString().trim();
        if (remoteSdpText.isEmpty()) return;

        String finalSdp = sanitizeSdp(remoteSdpText);
        if (finalSdp.isEmpty() || !finalSdp.startsWith("v=0")) return;

        SessionDescription.Type type = (peerConnection.getLocalDescription() == null)
                ? SessionDescription.Type.OFFER
                : SessionDescription.Type.ANSWER;

        try {
            SessionDescription remoteDesc = new SessionDescription(type, finalSdp);
            peerConnection.setRemoteDescription(new SdpObserverAdapter() {
                @Override
                public void onSetSuccess() {
                    if (type == SessionDescription.Type.OFFER) {
                        MediaConstraints constraints = new MediaConstraints();
                        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
                        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

                        peerConnection.createAnswer(new SdpObserverAdapter() {
                            @Override
                            public void onCreateSuccess(SessionDescription desc) {
                                peerConnection.setLocalDescription(new SdpObserverAdapter(), desc);
                            }
                        }, constraints);
                    } else {
                        updateStatus("Ответ успешно принят! Соединяемся...");
                    }
                }
            }, remoteDesc);
        } catch (Exception e) {
            Log.e("WebRTC_Test", "Ошибка Java", e);
        }
    }

    private void sendSdpToNetwork(String sdpDescription) {
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("type", "sdp");
            json.put("room", currentRoomId);
            json.put("sdp", sdpDescription);
            if (webSocket != null) webSocket.send(json.toString());
        } catch (Exception e) {
            Log.e("WebRTC_WS", "Ошибка отправки", e);
        }
    }

    private void setupDataChannelCallbacks() {
        if (dataChannel == null) return;
        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long l) {}
            @Override public void onStateChange() {}

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                ByteBuffer data = buffer.data;
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);

                try {
                    // Расшифровываем байты, используя тот же ключ комнаты
                    String decryptedMsg = CryptoHelper.decrypt(bytes, currentRoomId);

                    runOnUiThread(() -> tvChatHistory.append("Собеседник: " + decryptedMsg + "\n"));
                } catch (Exception e) {
                    Log.e("WebRTC_Crypto", "Ошибка расшифровки пакета", e);
                    runOnUiThread(() -> tvChatHistory.append("Система: Получено зашифрованное сообщение, но ключ не подошел.\n"));
                }
            }

        });
    }

    private void sendMessage() {
        String msg = etMessage.getText().toString().trim();
        if (!msg.isEmpty() && dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {
            try {
                // В качестве секретного ключа используем ID комнаты (currentRoomId)
                // Вы можете заменить "my_secret_password_" + currentRoomId для надежности
                byte[] encryptedBytes = CryptoHelper.encrypt(msg, currentRoomId);

                ByteBuffer buffer = ByteBuffer.wrap(encryptedBytes);
                buffer.rewind();

                dataChannel.send(new DataChannel.Buffer(buffer, false));

                tvChatHistory.append("Вы: " + msg + "\n");
                etMessage.setText("");
            } catch (Exception e) {
                Log.e("WebRTC_Crypto", "Ошибка шифрования сообщения", e);
                updateStatus("Ошибка защиты сообщения!");
            }
        }
    }


    private void updateStatus(String status) {
        runOnUiThread(() -> tvStatus.setText("Статус: " + status));
    }

    private String sanitizeSdp(String rawText) {
        if (rawText == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = rawText.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.contains("WebRTC_Test") && trimmed.contains(" : ")) {
                trimmed = trimmed.substring(trimmed.indexOf(" : ") + 3).trim();
            }
            if (trimmed.matches("^[a-z]=[\\s\\S]*")) {
                sb.append(trimmed).append("\r\n");
            }
        }
        String result = sb.toString();
        if (!result.endsWith("\r\n")) result += "\r\n";
        return result;
    }

    private static class CryptoHelper {
        private static final String ALGORITHM = "AES/GCM/NoPadding";
        private static final int TAG_LENGTH_BIT = 128;
        private static final int IV_LENGTH_BYTE = 12;

        // Метод шифрования строки
        public static byte[] encrypt(String plaintext, String secretKey) throws Exception {
            // Генерируем случайный вектор инициализации (IV) для каждой отправки
            byte[] iv = new byte[IV_LENGTH_BYTE];
            new java.security.SecureRandom().nextBytes(iv);

            // Инициализируем AES ключ (должен быть ровно 16, 24 или 32 байта)
            byte[] keyBytes = new byte[16];
            byte[] secretBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(secretBytes, 0, keyBytes, 0, Math.min(secretBytes.length, keyBytes.length));
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");

            // Настраиваем шифр
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(ALGORITHM);
            javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Объединяем IV и зашифрованный текст в один массив байт для отправки
            byte[] encryptedMessage = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, encryptedMessage, 0, iv.length);
            System.arraycopy(ciphertext, 0, encryptedMessage, iv.length, ciphertext.length);

            return encryptedMessage;
        }

        // Метод расшифровки байт
        public static String decrypt(byte[] encryptedMessage, String secretKey) throws Exception {
            // Извлекаем IV из начала массива
            byte[] iv = new byte[IV_LENGTH_BYTE];
            System.arraycopy(encryptedMessage, 0, iv, 0, iv.length);

            // Извлекаем сам зашифрованный текст
            int ciphertextLength = encryptedMessage.length - IV_LENGTH_BYTE;
            byte[] ciphertext = new byte[ciphertextLength];
            System.arraycopy(encryptedMessage, IV_LENGTH_BYTE, ciphertext, 0, ciphertextLength);

            // Инициализируем AES ключ аналогично шифрованию
            byte[] keyBytes = new byte[16];
            byte[] secretBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(secretBytes, 0, keyBytes, 0, Math.min(secretBytes.length, keyBytes.length));
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");

            // Настраиваем расшифровку
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(ALGORITHM);
            javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] plaintextBytes = cipher.doFinal(ciphertext);
            return new String(plaintextBytes, StandardCharsets.UTF_8);
        }
    }

    private static class SdpObserverAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription desc) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
