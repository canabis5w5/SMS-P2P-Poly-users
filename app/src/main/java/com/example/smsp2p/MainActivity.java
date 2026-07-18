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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "WebRTC_P2P";

    private EditText etSdpExchange, etMessage;
    private TextView tvStatus, tvChatHistory;

    private PeerConnectionFactory factory;

    // Групповые коллекции для хранения множественных соединений
    private final HashMap<String, PeerConnection> peerConnections = new HashMap<>();
    private final HashMap<String, DataChannel> dataChannels = new HashMap<>();

    private String myUserName = "Гость";

    private WebSocket webSocket;

    private boolean isInitiator = false;
    private String currentRoomId = "";
    private String myClientId = ""; // Выдается сервером
    private SharedPreferences prefs;
    private String wsServerUrl = "";

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "[MAIN] Запуск onCreate, инициализация UI элементов");

        prefs = getSharedPreferences("SMSP2P_PREFS", Context.MODE_PRIVATE);
        wsServerUrl = prefs.getString("ws_url", "ws://example.com:8888");

        etSdpExchange = findViewById(R.id.etSdpExchange);
        etMessage = findViewById(R.id.etMessage);
        tvStatus = findViewById(R.id.tvStatus);
        tvChatHistory = findViewById(R.id.tvChatHistory);

        Button btnCreateOffer = findViewById(R.id.btnCreateOffer);
        Button btnAcceptSdp = findViewById(R.id.btnAcceptSdp);
        Button btnSend = findViewById(R.id.btnSend);
        Button btnOpenSettings = findViewById(R.id.btnOpenSettings);

        tvChatHistory.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tvChatHistory.setOnTouchListener((v, event) -> {
            if (tvChatHistory.getLayout() != null) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });

        btnCreateOffer.setText("1. Создать новую комнату");
        btnAcceptSdp.setText("2. Войти по коду друга");
        etSdpExchange.setHint("Сюда вводите 6-значный код комнаты...");

        Log.d(TAG, "[MAIN] Инициализация ядра WebRTC библиотеки...");
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

        btnOpenSettings.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
        etSdpExchange.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                if (event.getRawX() >= (etSdpExchange.getRight() - etSdpExchange.getCompoundDrawables()[2].getBounds().width())) {
                    etSdpExchange.setText("");
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
        if (prefs != null) {
            wsServerUrl = prefs.getString("ws_url", "ws://example.com:8888");

            // Считываем обновленное имя. Если его нет в памяти — генерируем случайное "Участник_123"
            myUserName = prefs.getString("user_name", "Участник_" + (new Random().nextInt(900) + 100));
        }
        Log.d(TAG, "[LIFECYCLE] Актуальный никнейм: " + myUserName + ", адрес сокета: " + wsServerUrl);

        if (webSocket == null) {
            Log.d(TAG, "[LIFECYCLE] Инициируем чистое подключение к сокету...");
            initWebSocket();
        }
    }


    private void actionCreateNewRoom() {
        isInitiator = true;
        int code = new Random().nextInt(900000) + 100000;
        currentRoomId = String.valueOf(code);
        etSdpExchange.setText("ВАШ КОД: " + currentRoomId);
        updateStatus("Создана комната " + currentRoomId + ". Ожидаем участников...");
        sendJoinMessage();
    }

    private void actionJoinRoomByCode() {
        String enteredCode = etSdpExchange.getText().toString().trim();
        if (enteredCode.isEmpty() || enteredCode.length() < 6 || enteredCode.contains("ВАШ КОД")) {
            updateStatus("Введите корректный 6-значный код!");
            return;
        }
        isInitiator = false;
        currentRoomId = enteredCode;
        updateStatus("Вход в комнату " + currentRoomId + "...");
        sendJoinMessage();
    }

    private void sendJoinMessage() {
        try {
            org.json.JSONObject joinJson = new org.json.JSONObject();
            joinJson.put("type", "join");
            joinJson.put("room", currentRoomId);
            if (webSocket != null) {
                webSocket.send(joinJson.toString());
            } else {
                updateStatus("Ошибка: сервер недоступен.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка join JSON", e);
        }
    }

    private void initWebSocket() {
        if (webSocket != null || wsServerUrl == null || wsServerUrl.trim().isEmpty()) return;

        OkHttpClient client = new OkHttpClient();
        try {
            Request request = new Request.Builder().url(wsServerUrl).build();
            webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    webSocket = ws;
                    runOnUiThread(() -> updateStatus("Подключено к серверу обмена!"));
                }

                @Override
                public void onMessage(WebSocket ws, String text) {
                    try {
                        org.json.JSONObject json = new org.json.JSONObject(text);
                        String msgType = json.optString("type");

                        if ("welcome".equals(msgType)) {
                            myClientId = json.getString("yourId");
                        } else if ("room_status".equals(msgType)) {
                            int count = json.getInt("count");
                            runOnUiThread(() -> updateStatus("В комнате устройств: " + count));
                            if (isInitiator && json.has("new_client_id")) {
                                String newClient = json.getString("new_client_id");
                                runOnUiThread(() -> startPeerConnectionWith(newClient, true));
                            }
                        } else if ("user_left".equals(msgType)) {
                            String leftClientId = json.getString("leftClientId");
                            int count = json.optInt("count");
                            runOnUiThread(() -> {
                                handleUserDisconnect(leftClientId);
                                updateStatus("Осталось устройств: " + count);
                            });
                        } else if ("sdp".equals(msgType)) {
                            String senderId = json.getString("senderId");
                            org.json.JSONObject sdpObj = json.getJSONObject("sdp");
                            runOnUiThread(() -> handleReceivedSdp(senderId, sdpObj));
                        } else if ("candidate".equals(msgType)) {
                            String senderId = json.getString("senderId");
                            org.json.JSONObject candObj = json.getJSONObject("candidate");
                            runOnUiThread(() -> handleReceivedIceCandidate(senderId, candObj));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Ошибка разбора входящего JSON", e);
                    }
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    webSocket = null;
                    runOnUiThread(() -> updateStatus("Ошибка сети. Проверьте сервер."));
                }

                @Override
                public void onClosed(WebSocket ws, int code, String reason) {
                    webSocket = null;
                }
            });
        } catch (Exception e) {
            updateStatus("Неверный адрес сервера!");
        }
    }
    private void startPeerConnectionWith(final String remoteClientId, boolean shouldCreateOffer) {
        if (peerConnections.containsKey(remoteClientId)) return;

        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        String stunUrl = prefs != null ? prefs.getString("stun_url", "stun:://google.com") : "";

        if (!stunUrl.isEmpty()) {
            if (!stunUrl.startsWith("stun:")) {
                stunUrl = "stun:" + stunUrl;
            }
            iceServers.add(PeerConnection.IceServer.builder(stunUrl).createIceServer());
        }

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.disableIPv6OnWifi = true;
        rtcConfig.iceCandidatePoolSize = 0;
        rtcConfig.iceConnectionReceivingTimeout = 1000;
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;

        PeerConnection pc = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                sendIceCandidateToNetwork(remoteClientId, candidate);
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {}

            @Override
            public void onDataChannel(DataChannel channel) {
                dataChannels.put(remoteClientId, channel);
                setupDataChannelCallbacks(remoteClientId, channel);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                runOnUiThread(() -> {
                    if (state == PeerConnection.IceConnectionState.CONNECTED) {
                        updateStatus("P2P канал с " + remoteClientId + " открыт!");
                    } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                            state == PeerConnection.IceConnectionState.FAILED) {
                        handleUserDisconnect(remoteClientId);
                    }
                });
            }

            @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            @Override public void onAddStream(MediaStream mediaStream) {}
            @Override public void onRemoveStream(MediaStream mediaStream) {}
            @Override public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {}
            @Override public void onRenegotiationNeeded() {}
        });

        if (pc == null) return;
        peerConnections.put(remoteClientId, pc);

        if (shouldCreateOffer) {
            DataChannel.Init init = new DataChannel.Init();
            DataChannel dc = pc.createDataChannel("dataChannel_" + remoteClientId, init);
            dataChannels.put(remoteClientId, dc);
            setupDataChannelCallbacks(remoteClientId, dc);

            MediaConstraints constraints = new MediaConstraints();
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
            pc.createOffer(new SdpObserverWrapper(remoteClientId, pc, true), constraints);
        }
    }

    private void handleReceivedSdp(String senderId, org.json.JSONObject sdpObj) {
        try {
            String typeStr = sdpObj.getString("type");
            String description = sdpObj.getString("description");
            SessionDescription.Type type = SessionDescription.Type.fromCanonicalForm(typeStr);
            SessionDescription remoteSdp = new SessionDescription(type, description);

            if (type == SessionDescription.Type.OFFER) {
                startPeerConnectionWith(senderId, false);
            }

            PeerConnection pc = peerConnections.get(senderId);
            if (pc != null) {
                pc.setRemoteDescription(new SdpObserverWrapper(senderId, pc, false), remoteSdp);
                if (type == SessionDescription.Type.OFFER) {
                    MediaConstraints constraints = new MediaConstraints();
                    pc.createAnswer(new SdpObserverWrapper(senderId, pc, true), constraints);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка обработки входящего SDP", e);
        }
    }

    private void handleReceivedIceCandidate(String senderId, org.json.JSONObject candObj) {
        try {
            PeerConnection pc = peerConnections.get(senderId);
            if (pc != null) {
                IceCandidate candidate = new IceCandidate(
                        candObj.getString("sdpMid"),
                        candObj.getInt("sdpMLineIndex"),
                        candObj.getString("candidate")
                );
                pc.addIceCandidate(candidate);
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка добавления ICE-кандидата", e);
        }
    }
    private void setupDataChannelCallbacks(final String remoteClientId, DataChannel channel) {
        if (channel == null) return;

        channel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
                // Используется для контроля буфера при передаче тяжелых данных
            }

            @Override
            public void onStateChange() {
                Log.d(TAG, "[DataChannel] Статус канала с " + remoteClientId + " изменился на: " + channel.state().name());
                if (channel.state() == DataChannel.State.OPEN) {
                    runOnUiThread(() -> updateStatus("P2P канал связи активен."));
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                Log.d(TAG, "[DataChannel_EVENT] <<< ПОЛУЧЕН ПРЯМОЙ UDP-ПАКЕТ ОТ: " + remoteClientId);
                ByteBuffer data = buffer.data;
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);

                try {
                    // 1. Расшифровываем бинарный пакет в исходную JSON-строку
                    String decryptedJson = CryptoHelper.decrypt(bytes, currentRoomId);

                    // 2. Парсим внутренний P2P JSON, отправленный собеседником
                    org.json.JSONObject packet = new org.json.JSONObject(decryptedJson);
                    String senderName = packet.getString("senderName"); // Человеческое имя автора
                    String textMessage = packet.getString("text");       // Текст его сообщения

                    // 3. Выводим красивое человеческое имя в UI историю чата
                    runOnUiThread(() -> {
                        tvChatHistory.append(senderName + ": " + textMessage + "\n");
                        scrollChatToBottom();
                    });

                } catch (org.json.JSONException e) {
                    Log.e(TAG, "[P2P_PARSER] Ошибка структуры JSON внутри пакета от " + remoteClientId, e);
                    runOnUiThread(() -> {
                        tvChatHistory.append("Система: Получено некорректное сообщение от " + remoteClientId + ".\n");
                        scrollChatToBottom();
                    });
                } catch (Exception e) {
                    Log.e(TAG, "[Crypto] Критическая ошибка дешифрации пакета от " + remoteClientId, e);
                    runOnUiThread(() -> {
                        tvChatHistory.append("Система: Ошибка дешифрации приватного пакета.\n");
                        scrollChatToBottom();
                    });
                }
            }
        });
    }


    private void sendMessage() {
        String msg = etMessage.getText().toString().trim();
        if (msg.isEmpty()) return;

        if (dataChannels.isEmpty()) {
            updateStatus("В комнате пока никого нет.");
            return;
        }

        try {
            // Упаковываем ваше актуальное имя и текст в скрытый JSON
            org.json.JSONObject packet = new org.json.JSONObject();
            packet.put("senderName", myUserName);
            packet.put("text", msg);

            // Шифруем весь JSON целиком с помощью кода комнаты
            byte[] encryptedBytes = CryptoHelper.encrypt(packet.toString(), currentRoomId);
            int sendSuccessCount = 0;

            for (Map.Entry<String, DataChannel> entry : dataChannels.entrySet()) {
                DataChannel dc = entry.getValue();
                if (dc != null && dc.state() == DataChannel.State.OPEN) {
                    ByteBuffer buffer = ByteBuffer.wrap(encryptedBytes);
                    buffer.rewind();

                    if (dc.send(new DataChannel.Buffer(buffer, false))) {
                        sendSuccessCount++;
                    }
                }
            }

            if (sendSuccessCount > 0) {
                tvChatHistory.append("Вы: " + msg + "\n");
                etMessage.setText("");
                scrollChatToBottom();
            } else {
                updateStatus("Каналы связи еще не открылись (OPEN).");
            }
        } catch (Exception e) {
            Log.e(TAG, "[Crypto] Ошибка шифрования группового JSON", e);
            updateStatus("Ошибка защиты сообщения!");
        }
    }



    private void sendSdpToNetwork(String targetId, SessionDescription sdp) {
        if (webSocket == null) return;
        try {
            org.json.JSONObject payload = new org.json.JSONObject();
            payload.put("type", "sdp");
            payload.put("targetId", targetId);

            org.json.JSONObject sdpJson = new org.json.JSONObject();
            sdpJson.put("type", sdp.type.canonicalForm());
            sdpJson.put("description", sdp.description);
            payload.put("sdp", sdpJson);

            webSocket.send(payload.toString());
        } catch (Exception e) {
            Log.e(TAG, "Ошибка сборки SDP JSON", e);
        }
    }

    private void sendIceCandidateToNetwork(String targetId, IceCandidate candidate) {
        if (webSocket == null) return;
        try {
            org.json.JSONObject payload = new org.json.JSONObject();
            payload.put("type", "candidate");
            payload.put("targetId", targetId);

            org.json.JSONObject candJson = new org.json.JSONObject();
            candJson.put("sdpMid", candidate.sdpMid);
            candJson.put("sdpMLineIndex", candidate.sdpMLineIndex);
            candJson.put("candidate", candidate.sdp);
            payload.put("candidate", candJson);

            webSocket.send(payload.toString());
        } catch (Exception e) {
            Log.e(TAG, "Ошибка сборки Candidate JSON", e);
        }
    }

    private void handleUserDisconnect(String remoteClientId) {
        PeerConnection pc = peerConnections.remove(remoteClientId);
        if (pc != null) pc.close();

        DataChannel dc = dataChannels.remove(remoteClientId);
        if (dc != null) dc.close();

        tvChatHistory.append("Система: Пользователь " + remoteClientId + " покинул комнату.\n");
        scrollChatToBottom();
    }

    private void scrollChatToBottom() {
        if (tvChatHistory == null) return;
        int scrollAmount = tvChatHistory.getLayout() != null ?
                tvChatHistory.getLayout().getLineTop(tvChatHistory.getLineCount()) - tvChatHistory.getHeight() : 0;
        if (scrollAmount > 0) tvChatHistory.scrollTo(0, scrollAmount);
    }

    private void updateStatus(String status) {
        runOnUiThread(() -> tvStatus.setText("Статус: " + status));
    }

    private class SdpObserverWrapper implements SdpObserver {
        private final String remoteClientId;
        private final PeerConnection pc;
        private final boolean isLocalOffer;

        public SdpObserverWrapper(String remoteClientId, PeerConnection pc, boolean isLocalOffer) {
            this.remoteClientId = remoteClientId;
            this.pc = pc;
            this.isLocalOffer = isLocalOffer;
        }

        @Override
        public void onCreateSuccess(SessionDescription sdp) {
            pc.setLocalDescription(this, sdp);
        }

        @Override
        public void onSetSuccess() {
            SessionDescription localSdp = pc.getLocalDescription();
            if (localSdp != null && isLocalOffer) {
                sendSdpToNetwork(remoteClientId, localSdp);
            }
        }

        @Override public void onCreateFailure(String s) { Log.e(TAG, "Ошибка создания SDP: " + s); }
        @Override public void onSetFailure(String s) { Log.e(TAG, "Ошибка установки SDP: " + s); }
    }

    private static class CryptoHelper {
        private static final String ALGORITHM = "AES/GCM/NoPadding";
        private static final int TAG_LENGTH_BIT = 128;
        private static final int IV_LENGTH_BYTE = 12;

        public static byte[] encrypt(String plaintext, String secretKey) throws Exception {
            byte[] iv = new byte[IV_LENGTH_BYTE];
            new java.security.SecureRandom().nextBytes(iv);

            byte[] keyBytes = new byte[16];
            byte[] secretBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(secretBytes, 0, keyBytes, 0, Math.min(secretBytes.length, keyBytes.length));

            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(ALGORITHM);
            javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(TAG_LENGTH_BIT, iv);

            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] encryptedMessage = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, encryptedMessage, 0, iv.length);
            System.arraycopy(ciphertext, 0, encryptedMessage, iv.length, ciphertext.length);
            return encryptedMessage;
        }

        public static String decrypt(byte[] encryptedMessage, String secretKey) throws Exception {
            byte[] iv = new byte[IV_LENGTH_BYTE];
            System.arraycopy(encryptedMessage, 0, iv, 0, iv.length);

            int ciphertextLength = encryptedMessage.length - IV_LENGTH_BYTE;
            byte[] ciphertext = new byte[ciphertextLength];
            System.arraycopy(encryptedMessage, IV_LENGTH_BYTE, ciphertext, 0, ciphertextLength);

            byte[] keyBytes = new byte[16];
            byte[] secretBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(secretBytes, 0, keyBytes, 0, Math.min(secretBytes.length, keyBytes.length));

            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(ALGORITHM);
            javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(TAG_LENGTH_BIT, iv);

            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            byte[] plaintextBytes = cipher.doFinal(ciphertext);
            return new String(plaintextBytes, StandardCharsets.UTF_8);
        }
    }

    private String getAppVersion() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "v1.0.0";
        }
    }
}
