package com.example.smsp2p;

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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends AppCompatActivity {

    private EditText etSdpExchange, etMessage;
    private TextView tvStatus, tvChatHistory;
    private Button btnCreateOffer, btnAcceptSdp, btnSend;

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;

    // ПЕРЕМЕННЫЕ ДЛЯ АВТОМАТИЗАЦИИ СИГНАЛИНГА
    private WebSocket webSocket;
    private OkHttpClient client;
    private final String ROOM_ID = "my_secret_room_123";
    private final String WS_SERVER_URL = "ws://ipcamssss.online:8888";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etSdpExchange = findViewById(R.id.etSdpExchange);
        etMessage = findViewById(R.id.etMessage);
        tvStatus = findViewById(R.id.tvStatus);
        tvChatHistory = findViewById(R.id.tvChatHistory);
        btnCreateOffer = findViewById(R.id.btnCreateOffer);
        btnAcceptSdp = findViewById(R.id.btnAcceptSdp);
        btnSend = findViewById(R.id.btnSend);

        // 1. Инициализация WebRTC ядра (Ваш исходный блок)
        PeerConnectionFactory.InitializationOptions initOptions = PeerConnectionFactory.InitializationOptions
                .builder(getApplicationContext()).createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory();

        // 2. Настройка STUN-сервера
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:ipcamssss.online:3478").createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        // 3. Создание PeerConnection (Ваш исходный блок)
        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                runOnUiThread(() -> {
                    SessionDescription localDescription = peerConnection.getLocalDescription();
                    if (localDescription != null) {
                        etSdpExchange.setText(localDescription.description);
                        updateStatus("Код генерируется... Можно копировать!");
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
                            updateStatus("Код полностью готов! Отправьте его другу.");
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
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    updateStatus("Соединено напрямую через Интернет!");
                }
                if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    updateStatus("Связь разорвана.");
                }
            }

            @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            @Override public void onAddStream(MediaStream stream) {}
            @Override public void onRemoveStream(MediaStream stream) {}
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {}
            @Override public void onRenegotiationNeeded() {}
        });

        // Слушатели кликов
        btnCreateOffer.setOnClickListener(v -> createOffer());
        btnAcceptSdp.setOnClickListener(v -> acceptSdpFromEditText());
        btnSend.setOnClickListener(v -> sendMessage());

        // Запуск фонового сетевого рукопожатия
        initWebSocket();
    }

    private void initWebSocket() {
        client = new OkHttpClient();
        Request request = new Request.Builder().url(WS_SERVER_URL).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                updateStatus("Подключено к сигнальному серверу!");
                String joinMsg = "{\"type\":\"join\",\"room\":\"" + ROOM_ID + "\"}";
                webSocket.send(joinMsg);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    org.json.JSONObject json = new org.json.JSONObject(text);
                    String msgType = json.optString("type");

                    // ИСПРАВЛЕНО: Выводим на экран, зашел ли друг в комнату
                    if ("room_status".equals(msgType)) {
                        int devicesCount = json.getInt("count");
                        runOnUiThread(() -> {
                            if (devicesCount < 2) {
                                updateStatus("Вы один в комнате. Ожидание собеседника...");
                            } else {
                                updateStatus("Собеседник подключился! Можно создавать Оффер.");
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
                        runOnUiThread(() -> {
                            etSdpExchange.setText(remoteSdpText);
                            btnAcceptSdp.performClick();
                        });
                    }
                } catch (Exception e) {
                    Log.e("WebRTC_WS", "Ошибка авто-рукопожатия", e);
                }
            }

        });
    }

    private void sendSdpToNetwork(String sdpDescription) {
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("type", "sdp");
            json.put("room", ROOM_ID);
            json.put("sdp", sdpDescription);
            if (webSocket != null) webSocket.send(json.toString());
        } catch (Exception e) {
            Log.e("WebRTC_WS", "Ошибка отправки SDP", e);
        }
    }
    private void createOffer() {
        if (peerConnection == null) return;
        updateStatus("Генерация кода (STUN)...");

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
                        runOnUiThread(() -> {
                            etSdpExchange.setText(desc.description);
                            updateStatus("Базовый код готов! Пересылаем...");
                        });
                        sendSdpToNetwork(desc.description); // <--- Автоматически улетает в сеть
                    }
                }, desc);
            }
        }, constraints);
    }

    private void createAnswerAndSet() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        peerConnection.createAnswer(new SdpObserverAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription desc) {
                peerConnection.setLocalDescription(new SdpObserverAdapter() {
                    @Override
                    public void onSetSuccess() {
                        runOnUiThread(() -> {
                            etSdpExchange.setText(desc.description);
                            updateStatus("Ответ готов! Пересылаем обратно...");
                        });
                        sendSdpToNetwork(desc.description); // <--- Автоматически улетает в сеть
                    }
                }, desc);
            }
        }, constraints);
    }

    private void acceptSdpFromEditText() {
        if (peerConnection == null) return;
        String remoteSdpText = etSdpExchange.getText().toString().trim();
        if (remoteSdpText.isEmpty()) return;

        String finalSdp = sanitizeSdp(remoteSdpText);
        if (finalSdp.isEmpty() || !finalSdp.startsWith("v=0")) return;

        // Исправлено: Определение типа на основе наличия локального описания
        SessionDescription.Type type = (peerConnection.getLocalDescription() == null)
                ? SessionDescription.Type.OFFER
                : SessionDescription.Type.ANSWER;

        try {
            SessionDescription remoteDesc = new SessionDescription(type, finalSdp);
            peerConnection.setRemoteDescription(new SdpObserverAdapter() {
                @Override
                public void onSetSuccess() {
                    if (type == SessionDescription.Type.OFFER) {
                        createAnswerAndSet();
                    } else {
                        updateStatus("Ответ успешно принят! Соединяемся...");
                    }
                }
            }, remoteDesc);
        } catch (Exception e) {
            Log.e("WebRTC_Test", "Ошибка Java при установке описания", e);
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
                String msg = new String(bytes, StandardCharsets.UTF_8);
                runOnUiThread(() -> tvChatHistory.append("Собеседник: " + msg + "\n"));
            }
        });
    }

    private void sendMessage() {
        String msg = etMessage.getText().toString().trim();
        if (!msg.isEmpty() && dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {
            ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8));
            dataChannel.send(new DataChannel.Buffer(buffer, false));
            tvChatHistory.append("Вы: " + msg + "\n");
            etMessage.setText("");
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

    private static class SdpObserverAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription desc) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
