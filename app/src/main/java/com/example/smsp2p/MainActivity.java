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

public class MainActivity extends AppCompatActivity {

    private EditText etSdpExchange, etMessage;
    private TextView tvStatus, tvChatHistory;
    private Button btnCreateOffer, btnAcceptSdp, btnSend;

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;

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

        // 1. Инициализация WebRTC ядра
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

        // 3. Создание PeerConnection с обработчиками
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

        // Кнопка 1: Создание Оффера (Инициатор чата)
        btnCreateOffer.setOnClickListener(v -> {
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
                                updateStatus("Базовый код готов! Перешлите его другу.");
                            });
                        }
                    }, desc);
                }
            }, constraints);
        });

        btnAcceptSdp.setOnClickListener(v -> {
            Log.d("WebRTC_Test", "Кнопка 'Принять чужой код' нажата!");

            if (peerConnection == null) return;
            String remoteSdpText = etSdpExchange.getText().toString().trim();
            if (remoteSdpText.isEmpty()) return;

            if (dataChannel == null) {
                DataChannel.Init init = new DataChannel.Init();
                dataChannel = peerConnection.createDataChannel("sendDataChannel", init);
                setupDataChannelCallbacks();
                Log.d("WebRTC_Test", "Локальный каркас DataChannel инициализирован для Получателя.");
            }

            // Пропускаем текст через наш умный фильтр RFC
            String finalSdp = sanitizeSdp(remoteSdpText);

            if (finalSdp.isEmpty() || !finalSdp.startsWith("v=0")) {
                Log.e("WebRTC_Test", "Ошибка: После фильтрации текст пуст или не начинается с v=0!");
                runOnUiThread(() -> updateStatus("Ошибка: Некорректный или обрезанный код SDP"));
                return;
            }

            // Автоматически определяем тип по содержимому чистой строки
            SessionDescription.Type type;
            if (finalSdp.toLowerCase().contains("setup:actpass")) {
                type = SessionDescription.Type.OFFER;
            } else {
                type = SessionDescription.Type.ANSWER;
            }

            Log.d("WebRTC_Test", "Передаем отфильтрованный SDP. Тип: " + type.toString());
            Log.d("WebRTC_Test", "Финальный текст для ядра WebRTC:\n" + finalSdp);

            try {
                SessionDescription remoteDesc = new SessionDescription(type, finalSdp);

                peerConnection.setRemoteDescription(new SdpObserverAdapter() {
                    @Override
                    public void onSetSuccess() {
                        Log.d("WebRTC_Test", "setRemoteDescription выполнен УСПЕШНО!");
                        if (type == SessionDescription.Type.OFFER) {
                            createAnswerAndSet();
                        } else {
                            runOnUiThread(() -> updateStatus("Ответ успешно принят! Соединяемся..."));
                        }
                    }

                    @Override
                    public void onSetFailure(String error) {
                        Log.e("WebRTC_Test", "Ошибка WebRTC ядра: " + error);
                        runOnUiThread(() -> updateStatus("Ошибка WebRTC: " + error));
                    }
                }, remoteDesc);

            } catch (Exception e) {
                Log.e("WebRTC_Test", "Критическая ошибка Java", e);
                runOnUiThread(() -> updateStatus("Ошибка: " + e.getMessage()));
            }
        });

        // Кнопка 3: Отправка сообщений
        btnSend.setOnClickListener(v -> sendMessage());
    }

    private void createAnswerAndSet() {
        MediaConstraints constraints = new MediaConstraints();
        // Явно прописываем false, чтобы C++ не искал кодеки микрофона/камеры
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
                            updateStatus("Ответ готов! Перенесите его на Samsung.");
                        });
                    }
                }, desc);
            }

            @Override
            public void onCreateFailure(String error) {
                runOnUiThread(() -> updateStatus("Ошибка создания Answer: " + error));
            }
        }, constraints);
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
        if (!msg.isEmpty() && dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8));dataChannel.send(new DataChannel.Buffer(buffer, false));tvChatHistory.append("Вы: " + msg + "\n");etMessage.setText("");}}private void updateStatus(String status) {runOnUiThread(() -> tvStatus.setText("Статус: " + status));}private static class SdpObserverAdapter implements SdpObserver {@Override public void onCreateSuccess(SessionDescription desc) {}@Override public void onSetSuccess() {}@Override public void onCreateFailure(String error) {}@Override public void onSetFailure(String error) {}}
    private String sanitizeSdp(String rawText) {
        if (rawText == null) return "";
        StringBuilder sb = new StringBuilder();

        // Универсальное разделение по любым признакам конца строки
        String[] lines = rawText.split("\\r?\\n");

        for (String line : lines) {
            String trimmed = line.trim();

            // Очистка от возможных лог-префиксов Android Studio
            if (trimmed.contains("WebRTC_Test") && trimmed.contains(" : ")) {
                trimmed = trimmed.substring(trimmed.indexOf(" : ") + 3).trim();
            }

            // Оставляем только строки стандарта SDP: "буква=значение"
            if (trimmed.matches("^[a-z]=[\\s\\S]*")) {
                sb.append(trimmed).append("\r\n"); // Железно ставим CRLF в конце строки
            }
        }

        // ВАЖНО: стандарт WebRTC требует, чтобы весь блок SDP завершался финальным CRLF
        String result = sb.toString();
        if (!result.endsWith("\r\n")) {
            result += "\r\n";
        }

        return result;
    }


}