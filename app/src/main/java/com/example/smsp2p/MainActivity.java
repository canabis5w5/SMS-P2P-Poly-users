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

    private static final String TAG = "WebRTC_P2P";

    private EditText etSdpExchange, etMessage;
    private TextView tvStatus, tvChatHistory;
    private Button btnCreateOffer, btnAcceptSdp, btnSend;

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
        Log.d(TAG, "[MAIN] Запуск onCreate, инициализация UI элементов");

        prefs = getSharedPreferences("SMSP2P_PREFS", Context.MODE_PRIVATE);
        wsServerUrl = prefs.getString("ws_url", "ws://ipcamssss.online:8888");
        Log.d(TAG, "[MAIN] Считан адрес сигнального сервера из памяти: " + wsServerUrl);

        etSdpExchange = findViewById(R.id.etSdpExchange);
        etMessage = findViewById(R.id.etMessage);

        tvStatus = findViewById(R.id.tvStatus);
        tvChatHistory = findViewById(R.id.tvChatHistory);
        btnCreateOffer = findViewById(R.id.btnCreateOffer);
        btnAcceptSdp = findViewById(R.id.btnAcceptSdp);
        btnSend = findViewById(R.id.btnSend);
        Button btnOpenSettings = findViewById(R.id.btnOpenSettings);

        // Настройка вертикального скролла для истории сообщений
        tvChatHistory.setMovementMethod(new android.text.method.ScrollingMovementMethod());

        // Разделение конфликта жестов между TextView и ScrollView
        tvChatHistory.setOnTouchListener((v, event) -> {
            if (tvChatHistory.getLayout() != null) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });

        // Настройка названий кнопок для динамических комнат
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
        Log.d(TAG, "[MAIN] PeerConnectionFactory успешно создана");

        btnCreateOffer.setOnClickListener(v -> actionCreateNewRoom());
        btnAcceptSdp.setOnClickListener(v -> actionJoinRoomByCode());
        btnSend.setOnClickListener(v -> sendMessage());

        btnOpenSettings.setOnClickListener(v -> {
            Log.d(TAG, "[MAIN] Клик по кнопке Настроек, переход в SettingsActivity");
            android.content.Intent intent = new android.content.Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
        // Слушатель очистки поля по крестику
        etSdpExchange.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                if (event.getRawX() >= (etSdpExchange.getRight() - etSdpExchange.getCompoundDrawables()[2].getBounds().width())) {
                    etSdpExchange.setText("");
                    updateStatus("Поле обмена кодами очищено.");
                    Log.d(TAG, "[UI] Поле обмена SDP очищено пользователем через крестик");
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
            wsServerUrl = prefs.getString("ws_url", "ws://ipcamssss.online:8888");
        }
        Log.d(TAG, "[LIFECYCLE] Сработал onResume. Актуальный адрес сокета: " + wsServerUrl);

        // Переподключаем сокет принудительно, если он был закрыт или отсутствует
        if (webSocket == null) {
            Log.d(TAG, "[LIFECYCLE] webSocket равен null, инициируем чистое подключение...");
            initWebSocket();
        }
    }

    private void initPeerConnection() {
        if (peerConnection != null) {
            Log.d(TAG, "[WebRTC] Попытка повторной инициализации. peerConnection уже существует, пропускаем.");
            return;
        }
        Log.d(TAG, "[WebRTC] Начало сборки конфигурации PeerConnection...");

        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        String stunUrl = prefs != null ? prefs.getString("stun_url", "stun:ipcamssss.online:3478") : "";

        if (stunUrl != null && !stunUrl.isEmpty()) {
            if (!stunUrl.startsWith("stun:")) {
                stunUrl = "stun:" + stunUrl;
            }
            iceServers.add(PeerConnection.IceServer.builder(stunUrl).createIceServer());
            Log.d(TAG, "[WebRTC] В конфигурацию добавлен STUN сервер: " + stunUrl);
            updateStatus("Используем STUN: " + stunUrl);
        } else {
            Log.w(TAG, "[WebRTC] Адрес STUN пуст! Включается локальный режим Wi-Fi");
            updateStatus("Локальный режим (без STUN-сервера)");
        }

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        // Включаем жесткие раскомментированные оптимизации для ускорения сбора
        rtcConfig.disableIPv6OnWifi = true;
        rtcConfig.iceCandidatePoolSize = 0;
        rtcConfig.iceConnectionReceivingTimeout = 1000;
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        Log.d(TAG, "[WebRTC] Применены оптимизации rtcConfig: disableIPv6=true, poolSize=0, timeout=1000мс");

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                Log.d(TAG, "[ICE_EVENT] Сгенерирован промежуточный ICE-кандидат: " + candidate.sdp);
                runOnUiThread(() -> {
                    if (peerConnection != null) {
                        SessionDescription localDescription = peerConnection.getLocalDescription();
                        if (localDescription != null) {
                            etSdpExchange.setText(localDescription.description);
                        }
                    }
                });
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
                Log.d(TAG, "[ICE_EVENT] Изменение фазы сбора кандидатов. Новый статус: " + newState.name());
                if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                    Log.d(TAG, "[ICE_EVENT] !!! Сбор ICE полностью завершен (COMPLETE) !!!");
                    runOnUiThread(() -> {
                        if (peerConnection != null) {
                            SessionDescription localDescription = peerConnection.getLocalDescription();
                            if (localDescription != null) {
                                etSdpExchange.setText(localDescription.description);
                                updateStatus("Код готов! Отправляем...");
                                Log.d(TAG, "[СИГНАЛИНГ] Вызов фонового потока отправки готового SDP в сеть...");
                                new Thread(() -> sendSdpToNetwork(localDescription.description)).start();
                            }
                        }
                    });
                }
            }

            @Override
            public void onDataChannel(DataChannel channel) {
                Log.d(TAG, "[WebRTC_CALLBACK] Получен удаленный текстовый DataChannel от собеседника!");
                dataChannel = channel;
                setupDataChannelCallbacks();
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                Log.d(TAG, "[ICE_EVENT] Изменение статуса прямого соединения: " + state.name());
                runOnUiThread(() -> {
                    if (state == PeerConnection.IceConnectionState.CONNECTED) {
                        Log.d(TAG, "[ICE_EVENT] Успех! Прямой P2P-канал полностью открыт!");
                        updateStatus("Соединено напрямую через Интернет!");
                    } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        Log.w(TAG, "[ICE_EVENT] Внимание: Прямая связь с собеседником разорвана.");
                        updateStatus("Связь разорвана.");
                    }
                });
            }
            @Override public void onSignalingChange(PeerConnection.SignalingState state) {
                Log.d(TAG, "[WebRTC_CALLBACK] onSignalingChange: " + state.name());
            }
            @Override public void onIceConnectionReceivingChange(boolean b) {
                Log.d(TAG, "[WebRTC_CALLBACK] onIceConnectionChange Receiving: " + b);
            }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {
                Log.d(TAG, "[WebRTC_CALLBACK] Удалены ICE кандидаты в количестве: " + candidates.length);
            }
            @Override public void onAddStream(MediaStream stream) {}
            @Override public void onRemoveStream(MediaStream stream) {}
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {}
            @Override public void onRenegotiationNeeded() {
                Log.d(TAG, "[WebRTC_CALLBACK] Требуется пересогласование сессии (onRenegotiationNeeded)");
            }
        });
        Log.d(TAG, "[WebRTC] Объект peerConnection успешно создан");
    }

    private void actionCreateNewRoom() {
        Log.d(TAG, "[UI_CLICK] Нажата кнопка: 1. Создать новую комнату");
        initPeerConnection();
        isInitiator = true;
        int code = new Random().nextInt(900000) + 100000;
        currentRoomId = String.valueOf(code);
        etSdpExchange.setText("ВАШ КОД: " + currentRoomId);
        updateStatus("Создана комната " + currentRoomId + ". Передайте код другу.");
        Log.d(TAG, "[UI_CLICK] Сгенерирован локальный ID комнаты: " + currentRoomId + " (isInitiator = true)");

        String joinMsg = "{\"type\":\"join\",\"room\":\"" + currentRoomId + "\"}";
        if (webSocket != null) {
            Log.d(TAG, "[СИГНАЛИНГ] Отправка запроса join на сервер: " + joinMsg);
            webSocket.send(joinMsg);
        } else {
            Log.e(TAG, "[СИГНАЛИНГ] Ошибка отправки join: webSocket равен null!");
        }
    }

    private void actionJoinRoomByCode() {
        Log.d(TAG, "[UI_CLICK] Нажата кнопка: 2. Войти по коду друга");
        String enteredCode = etSdpExchange.getText().toString().trim();
        if (enteredCode.isEmpty() || enteredCode.length() < 6 || enteredCode.contains("ВАШ КОД")) {
            Log.w(TAG, "[UI_CLICK] Ошибка: Введен невалидный или пустой код комнаты: " + enteredCode);
            updateStatus("Введите корректный 6-значный код!");
            return;
        }

        initPeerConnection();
        isInitiator = false;
        currentRoomId = enteredCode;
        updateStatus("Вход в комнату " + currentRoomId + "...");
        Log.d(TAG, "[UI_CLICK] Зафиксирован вход в комнату: " + currentRoomId + " (isInitiator = false)");

        String joinMsg = "{\"type\":\"join\",\"room\":\"" + currentRoomId + "\"}";
        if (webSocket != null) {
            Log.d(TAG, "[СИГНАЛИНГ] Отправка запроса join на сервер: " + joinMsg);
            webSocket.send(joinMsg);
        } else {
            Log.e(TAG, "[СИГНАЛИНГ] Ошибка отправки join: webSocket равен null!");
        }
    }

    private void initWebSocket() {
        if (webSocket != null) {
            Log.d(TAG, "[SOCKET] Инициализация пропущена, активный сокет уже существует");
            return;
        }

        if (wsServerUrl == null || wsServerUrl.trim().isEmpty()) {
            Log.e(TAG, "[SOCKET] Ошибка запуска: Адрес ws_url в SharedPreferences пуст!");
            updateStatus("Ошибка: адрес сервера пуст. Задайте его в Настройках.");
            return;
        }

        String checkUrl = wsServerUrl.replace("ws://", "").replace("wss://", "").trim();
        if (checkUrl.isEmpty()) {
            Log.e(TAG, "[SOCKET] Ошибка запуска: Строка содержит только префикс схемы!");
            updateStatus("Ошибка: введите адрес хоста в Настройках.");
            return;
        }

        Log.d(TAG, "[SOCKET] Создание OkHttpClient и отправка WebSocket handshake к: " + wsServerUrl);
        client = new OkHttpClient();

        try {
            Request request = new Request.Builder().url(wsServerUrl).build();
            webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    webSocket = ws;
                    Log.d(TAG, "[SOCKET_EVENT] Сессия WebSocket успешно открыта (onOpen)!");
                    updateStatus("Подключено к серверу обмена!");
                }

                @Override
                public void onMessage(WebSocket ws, String text) {
                    Log.d(TAG, "[SOCKET_EVENT] <<< ВХОДЯЩИЙ ПАКЕТ С СЕРВЕРА: " + text);
                    try {
                        org.json.JSONObject json = new org.json.JSONObject(text);
                        String msgType = json.optString("type");

                        if ("room_status".equals(msgType)) {
                            int count = json.getInt("count");
                            Log.d(TAG, "[SOCKET_EVENT] Статус комнаты обновлен. Участников на сервере: " + count);
                            runOnUiThread(() -> {
                                if (count < 2) {
                                    updateStatus("Комната " + currentRoomId + ": Ожидание друга...");
                                } else {
                                    updateStatus("Друг на связи! Подключаемся автоматически...");
                                    if (isInitiator) {
                                        Log.d(TAG, "[СИГНАЛИНГ] Мы создатель чата. Автоматически запускаем генерацию Offer через 300мс...");
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
                            Log.d(TAG, "[SOCKET_EVENT] Получен удаленный SDP-код. Проверяем валидность...");

                            SessionDescription localDesc = peerConnection != null ? peerConnection.getLocalDescription() : null;
                            if (localDesc != null && sanitizeSdp(remoteSdpText).equals(sanitizeSdp(localDesc.description))) {
                                Log.d(TAG, "[SOCKET_EVENT] Игнорируем дубликат (эхо) собственного SDP-кода.");
                                return;
                            }

                            runOnUiThread(() -> {
                                etSdpExchange.setText(remoteSdpText);
                                Log.d(TAG, "[СИГНАЛИНГ] Передаем прилетевший SDP в обработчик acceptSdpFromEditText()");
                                acceptSdpFromEditText();
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "[SOCKET_EVENT] Ошибка разбора входящего JSON", e);
                    }
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    Log.e(TAG, "[SOCKET_EVENT] КРИТИЧЕСКИЙ СБОЙ СЕТИ WEBSOCKET: " + t.getMessage(), t);
                    webSocket = null;
                    runOnUiThread(() -> updateStatus("Ошибка сети. Проверьте сервер."));
                }

                @Override
                public void onClosed(WebSocket ws, int code, String reason) {
                    Log.d(TAG, "[SOCKET_EVENT] Сессия закрыта сервером: " + reason);
                    webSocket = null;
                }
            });

        } catch (IllegalArgumentException e) {
            Log.e(TAG, "[SOCKET] Неверный синтаксис URL", e);
            updateStatus("Неверный адрес сервера! Измените в Настройках.");
        }
    }

    private void triggerWebRtcOffer() {
        if (peerConnection == null) {
            Log.e(TAG, "[Offer] Ошибка: peerConnection равен null!");
            return;
        }
        Log.d(TAG, "[Offer] Инициация создания текстового DataChannel 'sendDataChannel'...");
        updateStatus("Генерация кода соединения (STUN)...");

        DataChannel.Init init = new DataChannel.Init();
        dataChannel = peerConnection.createDataChannel("sendDataChannel", init);
        setupDataChannelCallbacks();

        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        Log.d(TAG, "[Offer] Вызов peerConnection.createOffer()...");
        peerConnection.createOffer(new SdpObserverAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription desc) {
                if (peerConnection != null) {
                    peerConnection.setLocalDescription(new SdpObserverAdapter() {
                        @Override
                        public void onSetSuccess() {
                            // ИСПРАВЛЕНО: Убрали отсюда дублирующую отправку в сеть!
                            // Ждем, пока сработает onIceGatheringChange (COMPLETE)
                            runOnUiThread(() -> updateStatus("Сбор сетевых кандидатов..."));
                        }
                    }, desc);
                }
            }
        }, constraints);

    }

    private void acceptSdpFromEditText() {
        if (peerConnection == null) initPeerConnection();

        String remoteSdpText = etSdpExchange.getText().toString().trim();
        if (remoteSdpText.isEmpty()) return;

        String finalSdp = sanitizeSdp(remoteSdpText);
        if (finalSdp.isEmpty() || !finalSdp.startsWith("v=0")) {
            Log.w(TAG, "[СИГНАЛИНГ] Отмена acceptSdp: Текст не является валидным SDP (нет v=0)");
            return;
        }

        SessionDescription.Type type = (peerConnection.getLocalDescription() == null)
                ? SessionDescription.Type.OFFER
                : SessionDescription.Type.ANSWER;

        Log.d(TAG, "[СИГНАЛИНГ] Определен тип входящего пакета: " + type.name());

        try {
            SessionDescription remoteDesc = new SessionDescription(type, finalSdp);
            peerConnection.setRemoteDescription(new SdpObserverAdapter() {
                @Override
                public void onSetSuccess() {
                    Log.d(TAG, "[СИГНАЛИНГ] Чужой " + type.name() + " успешно применен в setRemoteDescription");
                    if (type == SessionDescription.Type.OFFER) {
                        Log.d(TAG, "[СИГНАЛИНГ] Это был Offer! Автоматически генерируем Answer в ответ...");
                        MediaConstraints constraints = new MediaConstraints();
                        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
                        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

                        peerConnection.createAnswer(new SdpObserverAdapter() {
                            @Override
                            public void onCreateSuccess(SessionDescription desc) {
                                if (peerConnection != null) {
                                    peerConnection.setLocalDescription(new SdpObserverAdapter() {
                                        @Override
                                        public void onSetSuccess() {
                                            // ИСПРАВЛЕНО: Убрали дублирующую отправку ответа!
                                            // Answer улетит в сеть автоматически, когда сбор закроется в COMPLETE
                                            runOnUiThread(() -> updateStatus("Генерация авто-ответа..."));
                                        }
                                    }, desc);
                                }
                            }
                        }, constraints);

                    } else {
                        Log.d(TAG, "[СИГНАЛИНГ] Сессия полностью согласована! Ждем открытия DataChannel...");
                        runOnUiThread(() -> updateStatus("Ответ успешно принят! Соединяемся..."));
                    }
                }
            }, remoteDesc);
        } catch (Exception e) {
            Log.e(TAG, "[СИГНАЛИНГ] Сбой применения remote sdp", e);
        }
    }

    private void sendSdpToNetwork(String sdpDescription) {
        Log.d(TAG, "[СИГНАЛИНГ] >>> ОТПРАВКА SDP ПАКЕТА НА СЕРВЕР...");
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("type", "sdp");
            json.put("room", currentRoomId);
            json.put("sdp", sdpDescription);
            if (webSocket != null) {
                webSocket.send(json.toString());
            } else {
                Log.e(TAG, "[СИГНАЛИНГ] Критическая ошибка: попытка отправки SDP в пустой веб-сокет!");
            }
        } catch (Exception e) {
            Log.e(TAG, "[СИГНАЛИНГ] Сбой отправки sdp JSON", e);
        }
    }

    private void setupDataChannelCallbacks() {
        if (dataChannel == null) return;
        Log.d(TAG, "[DataChannel] Регистрация колбэков для прямого канала сообщений...");
        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long l) {}
            @Override
            public void onStateChange() {
                if (dataChannel != null) {
                    Log.d(TAG, "[DataChannel_EVENT] Статус прямого канала связи изменился на: " + dataChannel.state().name());
                    if (dataChannel.state() == DataChannel.State.OPEN) {
                        runOnUiThread(() -> updateStatus("Чат готов к отправке сообщений!"));
                    }
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                Log.d(TAG, "[DataChannel_EVENT] <<< ПОЛУЧЕН ПРЯМОЙ UDP-ПАКЕТ ТЕКСТА ЧАТА!");
                ByteBuffer data = buffer.data;
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);

                try {
                    String decryptedMsg = CryptoHelper.decrypt(bytes, currentRoomId);
                    runOnUiThread(() -> {
                        tvChatHistory.append("Собеседник: " + decryptedMsg + "\n");
                        scrollChatToBottom();
                    });
                } catch (Exception e) {
                    Log.e(TAG, "[Crypto] Ошибка расшифровки входящих байт чата", e);
                    runOnUiThread(() -> tvChatHistory.append("Система: Ошибка дешифрации пакета.\n"));
                }
            }
        });
    }

    private void sendMessage() {
        String msg = etMessage.getText().toString().trim();
        if (msg.isEmpty()) return;

        if (dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {
            Log.d(TAG, "[UI_CHAT] Попытка отправки сообщения: " + msg);
            try {
                byte[] encryptedBytes = CryptoHelper.encrypt(msg, currentRoomId);
                ByteBuffer buffer = ByteBuffer.wrap(encryptedBytes);
                buffer.rewind();

                boolean success = dataChannel.send(new DataChannel.Buffer(buffer, false));
                if (success) {
                    tvChatHistory.append("Вы: " + msg + "\n");etMessage.setText("");
                    scrollChatToBottom();
                } else {
                    Log.e(TAG, "[UI_CHAT] Не удалось затолкнуть байты в DataChannel");
                }
            } catch (Exception e) {
                Log.e(TAG, "[Crypto] Ошибка шифрования сообщения", e);
                updateStatus("Ошибка защиты сообщения!");
            }
        } else {
            Log.w(TAG, "[UI_CHAT] Отмена отправки: Канал DataChannel еще не открыт (OPEN)!");
        }
    }
    private void scrollChatToBottom() {
        if (tvChatHistory == null) return;
        int scrollAmount = tvChatHistory.getLayout() != null ?tvChatHistory.getLayout().getLineTop(tvChatHistory.getLineCount()) - tvChatHistory.getHeight() : 0;
        if (scrollAmount > 0) {tvChatHistory.scrollTo(0, scrollAmount);
        }
    }
    private String sanitizeSdp(String rawText) {
        if (rawText == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = rawText.split("\r?\n");
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
            byte[] iv = new byte[IV_LENGTH_BYTE];System.arraycopy(encryptedMessage, 0, iv, 0, iv.length);
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
    private static class SdpObserverAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription desc) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {
            Log.e(TAG, "[SdpObserver] Сбой сборки SDP: " + error);
        }
        @Override public void onSetFailure(String error) {
            Log.e(TAG, "[SdpObserver] Сбой назначения локального/удаленного описания: " + error);
        }
    }
    private void updateStatus(String status) {
        runOnUiThread(() -> tvStatus.setText("Статус: " + status));
    }

}