package com.sabbir.aiphonedoctor;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.app.NotificationManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import android.graphics.Color;
import android.content.pm.PackageManager;

import org.json.JSONObject;
import org.json.JSONArray;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(12, 120, 75));
        getWindow().setNavigationBarColor(Color.BLACK);

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setBackgroundColor(Color.WHITE);
        webView.setWebViewClient(new WebViewClient());

        webView.addJavascriptInterface(
            new PhoneDoctorBridge(this),
            "PhoneDoctor"
        );

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    public class PhoneDoctorBridge {

        private final Context context;

        PhoneDoctorBridge(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public String getReport() {

            JSONObject report = new JSONObject();

            try {

                // Battery information
                Intent batteryIntent = context.registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                );

                if (batteryIntent != null) {

                    int level = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_LEVEL, -1
                    );

                    int scale = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_SCALE, -1
                    );

                    int status = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_STATUS, -1
                    );

                    int health = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_HEALTH, -1
                    );

                    int temperature = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_TEMPERATURE, -1
                    );

                    int percent = -1;

                    if (level >= 0 && scale > 0) {
                        percent = Math.round(level * 100f / scale);
                    }

                    boolean charging =
                        status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL;

                    report.put("battery", percent);
                    report.put("charging", charging);
                    report.put("batteryHealth", health);
                    report.put("batteryTemperature",
                        temperature >= 0 ? temperature / 10.0 : -1);
                }

                // Storage information
                StatFs stat = new StatFs(
                    Environment.getDataDirectory().getPath()
                );

                long total =
                    stat.getBlockCountLong() *
                    stat.getBlockSizeLong();

                long free =
                    stat.getAvailableBlocksLong() *
                    stat.getBlockSizeLong();

                report.put("storageTotal", total);
                report.put("storageFree", free);
                report.put("storageUsed", total - free);

                // Network information
                boolean connected = false;
                boolean internet = false;
                boolean wifi = false;

                ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(
                        Context.CONNECTIVITY_SERVICE
                    );

                if (cm != null) {

                    Network network = cm.getActiveNetwork();

                    if (network != null) {

                        NetworkCapabilities caps =
                            cm.getNetworkCapabilities(network);

                        if (caps != null) {

                            connected = caps.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_INTERNET
                            );

                            internet = caps.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED
                            );

                            wifi = caps.hasTransport(
                                NetworkCapabilities.TRANSPORT_WIFI
                            );
                        }
                    }
                }

                report.put("networkConnected", connected);
                report.put("internetAvailable", internet);
                report.put("wifiConnected", wifi);

                // This app's notification permission
                boolean notificationsEnabled = true;

                if (Build.VERSION.SDK_INT >= 24) {
                    NotificationManager nm =
                        (NotificationManager) context.getSystemService(
                            Context.NOTIFICATION_SERVICE
                        );

                    if (nm != null) {
                        notificationsEnabled =
                            nm.areNotificationsEnabled();
                    }
                }

                report.put("notificationsEnabled",
                    notificationsEnabled);

                // Check selected installed apps
                JSONArray apps = new JSONArray();

                addApp(apps, "WhatsApp", "com.whatsapp");
                addApp(apps, "IMO", "com.imo.android.imoim");
                addApp(apps, "Facebook", "com.facebook.katana");
                addApp(apps, "Messenger", "com.facebook.orca");
                addApp(apps, "Chrome", "com.android.chrome");
                addApp(apps, "YouTube", "com.google.android.youtube");

                report.put("apps", apps);
                report.put("success", true);

            } catch (Exception e) {

                try {
                    report.put("success", false);
                    report.put("error", "তথ্য পাওয়া যায়নি");
                } catch (Exception ignored) {
                }
            }

            return report.toString();
        }

        private void addApp(JSONArray apps, String name, String packageName) {

            JSONObject item = new JSONObject();

            try {

                boolean installed = false;

                try {
                    context.getPackageManager().getPackageInfo(
                        packageName, 0
                    );
                    installed = true;

                } catch (PackageManager.NameNotFoundException ignored) {
                    installed = false;
                }

                item.put("name", name);
                item.put("package", packageName);
                item.put("installed", installed);

                apps.put(item);

            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void openSettings(final String type) {

            runOnUiThread(() -> {

                Intent intent;

                try {

                    switch (type) {

                        case "battery":
                            intent = new Intent(
                                Settings.ACTION_BATTERY_SAVER_SETTINGS
                            );
                            break;

                        case "network":
                            intent = new Intent(
                                Settings.ACTION_WIFI_SETTINGS
                            );
                            break;

                        case "notifications":

                            if (Build.VERSION.SDK_INT >= 26) {
                                intent = new Intent(
                                    Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                );

                                intent.putExtra(
                                    Settings.EXTRA_APP_PACKAGE,
                                    getPackageName()
                                );

                            } else {
                                intent = new Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())
                                );
                            }
                            break;

                        case "permissions":
                        case "app":
                            intent = new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())
                            );
                            break;

                        case "storage":
                            intent = new Intent(
                                "android.settings.INTERNAL_STORAGE_SETTINGS"
                            );
                            break;

                        default:
                            intent = new Intent(Settings.ACTION_SETTINGS);
                            break;
                    }

                    startActivity(intent);

                } catch (Exception e) {

                    startActivity(
                        new Intent(Settings.ACTION_SETTINGS)
                    );
                }
            });
        }

        @JavascriptInterface
        public void openAppSettings(final String packageName) {

            runOnUiThread(() -> {

                String safePackage = null;

                if ("com.whatsapp".equals(packageName) ||
                    "com.imo.android.imoim".equals(packageName) ||
                    "com.facebook.katana".equals(packageName) ||
                    "com.facebook.orca".equals(packageName) ||
                    "com.android.chrome".equals(packageName) ||
                    "com.google.android.youtube".equals(packageName)) {

                    safePackage = packageName;
                }

                if (safePackage == null) return;

                try {

                    Intent intent;

                    if (Build.VERSION.SDK_INT >= 26) {

                        intent = new Intent(
                            Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        );

                        intent.putExtra(
                            Settings.EXTRA_APP_PACKAGE,
                            safePackage
                        );

                    } else {

                        intent = new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + safePackage)
                        );
                    }

                    startActivity(intent);

                } catch (Exception e) {

                    try {
                        startActivity(
                            new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + safePackage)
                            )
                        );
                    } catch (Exception ignored) {
                        startActivity(
                            new Intent(Settings.ACTION_SETTINGS)
                        );
                    }
                }
            });
        }
    }

    @Override
    public void onBackPressed() {

        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
                    }
