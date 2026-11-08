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
import android.provider.Settings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import android.graphics.Color;

import org.json.JSONObject;

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
                Intent batteryIntent = registerReceiver(
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

                    int percent = -1;

                    if (level >= 0 && scale > 0) {
                        percent = Math.round(level * 100f / scale);
                    }

                    boolean charging =
                        status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL;

                    report.put("battery", percent);
                    report.put("charging", charging);
                }

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

                if ("com.whatsapp".equals(packageName)) {
                    safePackage = "com.whatsapp";

                } else if ("com.imo.android.imoim".equals(packageName)) {
                    safePackage = "com.imo.android.imoim";

                } else if ("com.facebook.katana".equals(packageName)) {
                    safePackage = "com.facebook.katana";

                } else if ("com.google.android.youtube".equals(packageName)) {
                    safePackage = "com.google.android.youtube";
                }

                if (safePackage == null) {
                    return;
                }

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
                        Intent fallback = new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + safePackage)
                        );

                        startActivity(fallback);

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
