package com.example.smsp2p;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class P2PService extends Service {

    private static final String CHANNEL_ID = "P2P_NET_CHANNEL";
    private static final int NOTIFICATION_ID = 101;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Создаем уведомление, которое будет висеть в шторке, пока приложение свернуто
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SMS-P2P Мессенджер")
                .setContentText("Mesh-сеть активна. Вы остаетесь на связи в фоне.")
                .setSmallIcon(android.R.drawable.stat_notify_chat) // Стандартная иконка чата
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true) // Запрещает пользователю смахнуть уведомление
                .build();

        // Переводим сервис в режим Foreground (Защита от убийства системой)
        startForeground(NOTIFICATION_ID, notification);

        // START_STICKY говорит системе автоматически перезапустить сервис, если произойдет сбой
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "P2P Network Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
