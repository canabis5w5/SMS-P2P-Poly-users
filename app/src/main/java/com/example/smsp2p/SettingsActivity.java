package com.example.smsp2p;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private EditText etSettingsWs, etSettingsStun;
    private Button btnSaveSettings;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etSettingsWs = findViewById(R.id.etSettingsWs);
        etSettingsStun = findViewById(R.id.etSettingsStun);
        btnSaveSettings = findViewById(R.id.btnSaveSettings);

        prefs = getSharedPreferences("SMSP2P_PREFS", Context.MODE_PRIVATE);

        // Загружаем ранее сохраненные значения или ставим дефолтные
        etSettingsWs.setText(prefs.getString("ws_url", "ws://ipcamssss.online:8888"));
        etSettingsStun.setText(prefs.getString("stun_url", "stun:ipcamssss.online:3478"));

        btnSaveSettings.setOnClickListener(v -> {
            String wsUrl = etSettingsWs.getText().toString().trim();
            String stunUrl = etSettingsStun.getText().toString().trim();

            // ЗАЩИТА: Проверяем на пустоту ДО добавления префикса ws://
            if (wsUrl.isEmpty()) {
                etSettingsWs.setError("Адрес сервера не может быть пустым!");
                return;
            }

            if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
                wsUrl = "ws://" + wsUrl;
            }

            // Сохраняем данные в память
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("ws_url", wsUrl);
            editor.putString("stun_url", stunUrl);
            editor.apply();

            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();

            // Закрываем активити и возвращаемся на главный экран
            finish();
        });
    }
}
