package com.sabbir.aiphonedoctor;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.AppOpsManager;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageEvents;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.BatteryManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Environment;
import android.os.StatFs;

import java.util.HashMap;
import java.util.Map;

public class AppMonitorService extends Service {

    public static final String ACTION_START =
            "com.sabbir.aiphonedoctor.START_MONITOR";

    public static final String ACTION_STOP =
            "com.sabbir.aiphonedoctor.STOP_MONITOR";

    private static final String CHANNEL_ID = "phone_doctor_monitor";
    private static final String ALERT_CHANNEL_ID = "phone_doctor_alerts";

    private static final int SERVICE_NOTIFICATION_ID = 1001;

    private static final long CHECK_INTERVAL = 2000;
    private static final long ALERT_COOLDOWN = 30 * 60 * 1000;

    private Handler handler;
    private UsageStatsManager usageManager;

    private long lastQueryTime = 0;
    private long lastEventTime = 0;

    private String lastEventKey = "";

    private boolean monitoring = false;

    private final Map<String, String> supportedApps = new HashMap<>();

    private final Runnable monitorRunnable = new Runnable() {
        @Override
        public void run() {

            if (!monitoring) return;

            try {
                detectOpenedApps();
            } catch (Exception ignored) {
            }

            if (monitoring && handler != null) {
                handler.postDelayed(this, CHECK_INTERVAL);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        handler = new Handler(Looper.getMainLooper());

        usageManager = (UsageStatsManager)
                getSystemService(Context.USAGE_STATS_SERVICE);

        supportedApps.put("com.whatsapp", "WhatsApp");
        supportedApps.put("com.imo.android.imoim", "IMO");
        supportedApps.put("com.facebook.katana", "Facebook");
        supportedApps.put("com.facebook.orca", "Messenger");
        supportedApps.put("com.android.chrome", "Chrome");
        supportedApps.put("com.google.android.youtube", "YouTube");

        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {

            monitoring = false;

            if (handler != null) {
                handler.removeCallbacks(monitorRunnable);
            }

            stopForeground(true);
            stopSelf();

            return START_NOT_STICKY;
        }

        if (!hasUsageAccess()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {

            Notification notification = createServiceNotification();

            if (Build.VERSION.SDK_INT >= 34) {

                startForeground(
                        SERVICE_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                );

            } else {

                startForeground(
                        SERVICE_NOTIFICATION_ID,
                        notification
                );
            }

        } catch (Exception e) {

            stopSelf();
            return START_NOT_STICKY;
        }

        if (!monitoring) {

            monitoring = true;

            lastQueryTime = System.currentTimeMillis() - 5000;
            lastEventTime = 0;
            lastEventKey = "";

            handler.post(monitorRunnable);
        }

        return START_STICKY;
    }

    private boolean hasUsageAccess() {

        try {

            AppOpsManager appOps =
                    (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);

            if (appOps == null) return false;

            int mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    getPackageName()
            );

            return mode == AppOpsManager.MODE_ALLOWED;

        } catch (Exception e) {

            return false;
        }
    }

    private void detectOpenedApps() {

        if (usageManager == null) return;

        long now = System.currentTimeMillis();

        long begin = Math.max(
                0,
                lastQueryTime - 2500
        );

        UsageEvents events = usageManager.queryEvents(begin, now);

        lastQueryTime = now;

        if (events == null) return;

        UsageEvents.Event event = new UsageEvents.Event();

        while (events.hasNextEvent()) {

            events.getNextEvent(event);

            int eventType = event.getEventType();

            boolean appOpened = false;

            if (Build.VERSION.SDK_INT >= 29) {

                appOpened =
                        eventType == UsageEvents.Event.ACTIVITY_RESUMED;

            } else {

                appOpened =
                        eventType == UsageEvents.Event.MOVE_TO_FOREGROUND;
            }

            if (!appOpened) continue;

            String packageName = event.getPackageName();

            if (packageName == null) continue;

            if (!supportedApps.containsKey(packageName)) continue;

            long eventTime = event.getTimeStamp();

            String eventKey = eventTime + "_" + packageName;

            if (eventTime < lastEventTime) continue;

            if (eventKey.equals(lastEventKey)) continue;

            lastEventTime = eventTime;
            lastEventKey = eventKey;

            checkAppProblems(packageName);
        }
    }

    private void checkAppProblems(String packageName) {

        String appName = supportedApps.get(packageName);

        if (appName == null) return;

        checkInternet(appName, packageName);

        checkStorage(appName, packageName);

        checkBatteryTemperature(appName, packageName);
    }

    private void checkInternet(String appName, String packageName) {

        boolean internetAvailable = false;

        try {

            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(
                            Context.CONNECTIVITY_SERVICE
                    );

            if (cm != null) {

                Network network = cm.getActiveNetwork();

                if (network != null) {

                    NetworkCapabilities caps =
                            cm.getNetworkCapabilities(network);

                    if (caps != null) {

                        internetAvailable =
                                caps.hasCapability(
                                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                                )
                                &&
                                caps.hasCapability(
                                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                                );
                    }
                }
            }

        } catch (Exception ignored) {
        }

        if (!internetAvailable) {

            sendProblem(
                    packageName,
                    "internet",
                    appName + "-এ ইন্টারনেট সমস্যা",
                    appName + " খোলার সময় ফোনে কার্যকর ইন্টারনেট সংযোগ পাওয়া যায়নি। অনলাইন সুবিধাগুলো কাজ নাও করতে পারে।",
                    "১. Wi-Fi অথবা মোবাইল ডাটা চালু আছে কি না দেখুন।\n২. অন্য কোনো ওয়েবসাইট খুলে পরীক্ষা করুন।\n৩. প্রয়োজন হলে নেটওয়ার্ক পুনরায় সংযোগ করুন।"
            );
        }
    }

    private void checkStorage(String appName, String packageName) {

        try {

            StatFs stat = new StatFs(
                    Environment.getDataDirectory().getPath()
            );

            long total =
                    stat.getBlockCountLong() *
                    stat.getBlockSizeLong();

            long free =
                    stat.getAvailableBlocksLong() *
                    stat.getBlockSizeLong();

            if (total > 0) {

                double freePercent =
                        (free * 100.0) / total;

                if (freePercent < 10) {

                    sendProblem(
                            packageName,
                            "storage",
                            "ফোনের স্টোরেজ প্রায় পূর্ণ",
                            appName + " ব্যবহারের সময় দেখা গেছে ফোনে খালি জায়গা ১০ শতাংশের কম। ছবি, ভিডিও বা ফাইল সংরক্ষণে সমস্যা হতে পারে।",
                            "১. অপ্রয়োজনীয় ফাইল পর্যালোচনা করুন।\n২. প্রয়োজন নেই এমন ডাউনলোড মুছে দিন।\n৩. গুরুত্বপূর্ণ ফাইল ব্যাকআপ রাখুন।"
                    );
                }
            }

        } catch (Exception ignored) {
        }
    }

    private void checkBatteryTemperature(
            String appName,
            String packageName
    ) {

        try {

            Intent battery = registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            );

            if (battery == null) return;

            int temperature = battery.getIntExtra(
                    BatteryManager.EXTRA_TEMPERATURE,
                    -1
            );

            if (temperature >= 450) {

                double celsius = temperature / 10.0;

                sendProblem(
                        packageName,
                        "temperature",
                        "ফোন অতিরিক্ত গরম হচ্ছে",
                        appName + " ব্যবহারের সময় ব্যাটারির তাপমাত্রা প্রায় "
                                + celsius + "°C পাওয়া গেছে।",
                        "১. কিছুক্ষণ ফোন ব্যবহার বন্ধ রাখুন।\n২. চার্জে থাকলে নিরাপদভাবে চার্জার খুলে রাখুন।\n৩. ফোন ঠান্ডা হতে দিন।"
                );
            }

        } catch (Exception ignored) {
        }
    }

    private void sendProblem(
            String packageName,
            String issueKey,
            String title,
            String message,
            String steps
    ) {

        String preferenceKey =
                "alert_" + packageName + "_" + issueKey;

        android.content.SharedPreferences prefs =
                getSharedPreferences("doctor_alerts", MODE_PRIVATE);

        long lastAlert = prefs.getLong(preferenceKey, 0);

        long now = System.currentTimeMillis();

        if (now - lastAlert < ALERT_COOLDOWN) {
            return;
        }

        prefs.edit()
                .putLong(preferenceKey, now)
                .apply();

        try {

            Intent openIntent = new Intent(
                    this,
                    MainActivity.class
            );

            openIntent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            );

            openIntent.putExtra("diagnostic_title", title);
            openIntent.putExtra("diagnostic_message", message);
            openIntent.putExtra("diagnostic_steps", steps);
            openIntent.putExtra("diagnostic_app", packageName);

            int requestCode =
                    Math.abs((packageName + issueKey).hashCode());

            PendingIntent pendingIntent =
                    PendingIntent.getActivity(
                            this,
                            requestCode,
                            openIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT |
                            PendingIntent.FLAG_IMMUTABLE
                    );

            Notification.Builder builder;

            if (Build.VERSION.SDK_INT >= 26) {

                builder = new Notification.Builder(
                        this,
                        ALERT_CHANNEL_ID
                );

            } else {

                builder = new Notification.Builder(this);
                builder.setPriority(Notification.PRIORITY_HIGH);
            }

            Notification notification = builder
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(
                            new Notification.BigTextStyle()
                                    .bigText(message + "\n\n" + steps)
                    )
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ERROR)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .build();

            NotificationManager manager =
                    (NotificationManager) getSystemService(
                            Context.NOTIFICATION_SERVICE
                    );

            if (manager != null) {

                int notificationId =
                        2000 + Math.abs(
                                (packageName + issueKey).hashCode() % 100000
                        );

                manager.notify(notificationId, notification);
            }

        } catch (Exception ignored) {
        }
    }

    private void createNotificationChannels() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationManager manager =
                    (NotificationManager) getSystemService(
                            Context.NOTIFICATION_SERVICE
                    );

            if (manager == null) return;

            NotificationChannel serviceChannel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Phone Doctor Monitoring",
                            NotificationManager.IMPORTANCE_LOW
                    );

            serviceChannel.setDescription(
                    "Phone Doctor background monitoring"
            );

            NotificationChannel alertChannel =
                    new NotificationChannel(
                            ALERT_CHANNEL_ID,
                            "Phone Doctor Problem Alerts",
                            NotificationManager.IMPORTANCE_HIGH
                    );

            alertChannel.setDescription(
                    "Detected phone and app problems"
            );

            manager.createNotificationChannel(serviceChannel);
            manager.createNotificationChannel(alertChannel);
        }
    }

    private Notification createServiceNotification() {

        Intent openIntent = new Intent(
                this,
                MainActivity.class
        );

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        1001,
                        openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT |
                        PendingIntent.FLAG_IMMUTABLE
                );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= 26) {

            builder = new Notification.Builder(
                    this,
                    CHANNEL_ID
            );

        } else {

            builder = new Notification.Builder(this);
        }

        return builder
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("SABBIR AI PHONE DOCTOR")
                .setContentText("ব্যাকগ্রাউন্ডে অ্যাপের সমস্যা পরীক্ষা চলছে")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {

        monitoring = false;

        if (handler != null) {
            handler.removeCallbacks(monitorRunnable);
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    }
