package com.example.tcsle;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    // Services
    private PDRService pdrService;
    private RouteManager routeManager;
    // 🆕 最後のADVERTISE時のセンサーデータ保存用
    private PDRService.SensorData lastAdvertiseData = null;

    // BLE関連
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser bluetoothLeAdvertiser;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<Intent> resultLauncher;
    private byte advFlag;

    // UI要素
    private TextView tvStepCount, tvDistance, tvHeading;
    private Button btnMainAction, btnReset, btnBLEfinish;
    private TextView tvRouteInfo, tvTrialNumber, tvCurrentPoint;
    private MaterialSwitch switchBLE;
    private Spinner spinnerRoute;

    // 状態管理
    public Handler handler;
    private Runnable updateRunnable;
    private boolean bleFlag = false;
    private boolean isTracking = false;
    private ArrayAdapter<RouteManager.RoutePreset> routeAdapter;

    // Foreground Service関連
    private PDRForegroundService pdrForegroundService;
    private boolean serviceBound = false;
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 1001;
    private static final int REQUEST_CODE_BATTERY_OPTIMIZATION = 1002;

    // ServiceConnection
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PDRForegroundService.LocalBinder binder = (PDRForegroundService.LocalBinder) service;
            pdrForegroundService = binder.getService();
            serviceBound = true;
            Log.d(TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            pdrForegroundService = null;
            Log.d(TAG, "Service disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 通知チャンネル作成（Android 8.0以降必須）
        createNotificationChannel();

        // 通知権限のリクエスト（Android 13以降）
        requestNotificationPermission();

        // バッテリー最適化除外のリクエスト
        requestBatteryOptimizationExemption();

        initializeServices();
        initializeViews();
        setupBLE();
        setupRouteUI();
        setupButtons();
        setupHandler();
    }

    /**
     * 通知チャンネルの作成（Android 8.0以降必須）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    PDRForegroundService.CHANNEL_ID,
                    "PDR測定サービス",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("PDR測定中の通知を表示します");
            channel.setShowBadge(false);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created");
            }
        }
    }

    /**
     * 通知権限のリクエスト（Android 13以降必須）
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_CODE_POST_NOTIFICATIONS
                );
            }
        }
    }

    /**
     * バッテリー最適化除外のリクエスト（測定精度向上）
     */
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));

                try {
                    startActivityForResult(intent, REQUEST_CODE_BATTERY_OPTIMIZATION);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to request battery optimization exemption", e);
                }
            }
        }
    }

    private void initializeServices() {
        routeManager = new RouteManager(this);
        pdrService = new PDRService(this);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    private void initializeViews() {
        // センサーデータ表示用
        tvStepCount = findViewById(R.id.stepCountTextView);
        tvDistance = findViewById(R.id.distanceTextView);
        tvHeading = findViewById(R.id.headingTextView);

        // ボタン類
        btnMainAction = findViewById(R.id.btnMainAction);
        btnReset = findViewById(R.id.resetButton);
        btnBLEfinish = findViewById(R.id.finishButton);
        switchBLE = findViewById(R.id.switchBLE);

        // ルート管理用
        spinnerRoute = findViewById(R.id.spinnerRoute);
        tvRouteInfo = findViewById(R.id.tvRouteInfo);
        tvTrialNumber = findViewById(R.id.tvTrialNumber);
        tvCurrentPoint = findViewById(R.id.tvCurrentPoint);

        switchBLE.setChecked(false);
    }

    private void setupBLE() {
        // パーミッションリクエストランチャーの初期化
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        if (advFlag != (byte) 0xCC) {
                            startBLEAdvertising(advFlag);
                        } else {
                            stopBLEAdvertising();
                        }
                    } else {
                        Toast.makeText(this, "Need BLUETOOTH_ADMIN permission.", Toast.LENGTH_SHORT).show();
                    }
                });

        // Bluetooth有効化ランチャーの初期化
        resultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
                        if (bluetoothLeAdvertiser == null) {
                            Toast.makeText(this, "Bluetooth LE is not available on this device.", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(this, "Bluetooth is disabled.", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void setupRouteUI() {
        List<RouteManager.RoutePreset> routes = routeManager.getAllRoutes();
        routeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, routes);
        routeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRoute.setAdapter(routeAdapter);

        spinnerRoute.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!routeManager.isMeasuring()) {
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupButtons() {
        // 巨大メインボタン
        btnMainAction.setOnClickListener(v -> handleMainButtonClick());

        // リセットボタン
        btnReset.setOnClickListener(v -> resetTracking());

        // BLE終了信号ボタン
        btnBLEfinish.setOnClickListener(v -> finishBLEAdvertising());

        // BLEスイッチ
        switchBLE.setOnCheckedChangeListener((buttonView, isChecked) -> {
            bleFlag = isChecked;
            btnBLEfinish.setEnabled(isChecked);
        });
    }

    /**
     * 巨大メインボタンのクリック処理
     */
    private void handleMainButtonClick() {

        if (!routeManager.isMeasuring()) {
            // 待機中 → 測定開始
            startTracking();
        } else if (!routeManager.isLastPoint()) {
            // 測定中・中間地点 → 地点通過
            passPoint();
        } else {
            // 測定中・最終地点 → 測定完了
            completeTracking();
        }

        // ボタン表示更新
        updateMainButton();
    }

    /**
     * 巨大メインボタンの表示更新
     */
    private void updateMainButton() {
        if (!routeManager.isMeasuring()) {
            // 状態1: 待機中
            RouteManager.RoutePreset selectedRoute = (RouteManager.RoutePreset) spinnerRoute.getSelectedItem();
            String routeName = selectedRoute != null ? selectedRoute.getRouteName() : "ルート未選択";

            btnMainAction.setText("測定開始\n" + routeName);
            btnMainAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF28a745));
            btnMainAction.setEnabled(selectedRoute != null && selectedRoute.isValid());

        } else if (!routeManager.isLastPoint()) {
            // 状態2-N: 測定中・中間地点
            RouteManager.RoutePoint targetPoint = routeManager.getCurrentTargetPoint();

            String buttonText = String.format("地点%d通過\n\n目標: (%.1f, %.1f)\n現在: (%.2f, %.2f)\n\nアドバタイズ %s",
                    routeManager.getCurrentRoutePoint(),
                    targetPoint != null ? targetPoint.getX() : 0.0f,
                    targetPoint != null ? targetPoint.getY() : 0.0f,
                    pdrService.getX(),
                    pdrService.getY(),
                    routeManager.getAdvertiseCountText()
            );

            btnMainAction.setText(buttonText);
            btnMainAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF007bff));
            btnMainAction.setEnabled(true);

        } else {
            // 状態N+1: 測定中・最終地点
            RouteManager.RoutePoint targetPoint = routeManager.getCurrentTargetPoint();

            String buttonText = String.format("測定完了\n\n最終地点: (%.1f, %.1f)\n現在: (%.2f, %.2f)\n\nアドバタイズ %s",
                    targetPoint != null ? targetPoint.getX() : 0.0f,
                    targetPoint != null ? targetPoint.getY() : 0.0f,
                    pdrService.getX(),
                    pdrService.getY(),
                    routeManager.getAdvertiseCountText()
            );

            btnMainAction.setText(buttonText);
            btnMainAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFdc3545));
            btnMainAction.setEnabled(true);
        }
    }

    private void setupHandler() {
        handler = new Handler();
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateUI();
                handler.postDelayed(this, 10);
            }
        };
    }

    // =========================== BLE機能 ===========================

    @Override
    protected void onResume() {
        super.onResume();
        resumeBLE();
    }

    private void resumeBLE() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_SHORT).show();
            return;
        } else if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            resultLauncher.launch(enableBtIntent);
        } else {
            bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            if (bluetoothLeAdvertiser == null) {
                Toast.makeText(this, "Bluetooth LE is not available", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startBLEAdvertising(byte flag) {
        byte[] manufacturerData = createManufacturerData(flag);
        ParcelUuid serviceUuid = new ParcelUuid(UUID.fromString("00001821-0000-1000-8000-00805F9B34FB"));

        AdvertiseData advertiseData = new AdvertiseData.Builder()
                .addManufacturerData(0x027d, manufacturerData)
                .addServiceUuid(serviceUuid)
                .build();

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        advFlag = flag;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_ADMIN);
        } else {
            Log.d(TAG, "Starting advertisement with flag: 0x" + String.format("%02X", flag));
            bluetoothLeAdvertiser.startAdvertising(settings, advertiseData, advertiseCallback);
        }
    }

    private void stopBLEAdvertising() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_ADMIN);
        } else {
            Log.d(TAG, "Stopping advertisement");
            bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
        }
    }

    private byte[] createManufacturerData(byte flag) {
        Calendar calendar = Calendar.getInstance();
        ByteBuffer buffer = ByteBuffer.allocate(12);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) (calendar.get(Calendar.MONTH)+1));
        buffer.put((byte) calendar.get(Calendar.DAY_OF_MONTH));
        buffer.put((byte) calendar.get(Calendar.HOUR_OF_DAY));
        buffer.put((byte) calendar.get(Calendar.MINUTE));
        buffer.put((byte) calendar.get(Calendar.SECOND));
        buffer.putShort((short) calendar.get(Calendar.MILLISECOND));

        // PDRから現在位置を取得
        int pos_x = 0, pos_y = 0;
        if (pdrService != null) {
            pos_x = (int) Math.round(pdrService.getX() * 1000);
            pos_y = (int) Math.round(pdrService.getY() * 1000);
            pos_x = Math.max(-32767, Math.min(32767, pos_x));
            pos_y = Math.max(-32767, Math.min(32767, pos_y));
        }

        buffer.putShort((short) pos_x);
        buffer.putShort((short) pos_y);
        buffer.put(flag);

        return buffer.array();
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            Log.d(TAG, "Advertising started successfully");
        }
        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "Advertising failed: " + errorCode);
            super.onStartFailure(errorCode);
        }
    };

    // =========================== トラッキング制御 ===========================

    private void startTracking() {
        RouteManager.RoutePreset selectedRoute = (RouteManager.RoutePreset) spinnerRoute.getSelectedItem();
        if (selectedRoute == null || !selectedRoute.isValid()) {
            Toast.makeText(this, "有効なルートを選択してください", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isTracking) {
            // 測定開始前に必ずリセット（二重保険）
            pdrService.reset();

            // ルートの開始地点を初期位置として設定
            RouteManager.RoutePoint firstPoint = selectedRoute.getRoutePoint(0);
            if (firstPoint != null) {
                pdrService.setInitialPosition(firstPoint.getX(), firstPoint.getY());
                Log.d(TAG, String.format("Initial position set to: (%.1f, %.1f)",
                        firstPoint.getX(), firstPoint.getY()));
            }

            routeManager.startMeasurement(selectedRoute.getRouteId());
            pdrService.setRouteInfo(selectedRoute.getRouteId(), routeManager.getCurrentTrialNumber());
            isTracking = true;
        }

        pdrService.start();
        recordRouteEvent("START");
        routeManager.executeAdvertise();
        recordRouteEvent("ADVERTISE");

        // 🆕 START時のADVERTISEデータを保存
        lastAdvertiseData = pdrService.getCurrentData();

        if (bleFlag) {
            single400msBLEAdvertise((byte) 0xBE);
        }

        // Foreground Service起動
        startForegroundService();

        btnReset.setEnabled(true);
        spinnerRoute.setEnabled(false);
        handler.post(updateRunnable);
        updateMainButton();
        updateStatusBar();
    }

    /**
     * 中間地点通過処理
     */
    private void passPoint() {
        if (!routeManager.isMeasuring()) {
            Toast.makeText(this, "測定を開始してください", Toast.LENGTH_SHORT).show();
            return;
        }

        // 先に次の地点へ進行（RoutePoint更新）
        if (!routeManager.isLastPoint()) {
            routeManager.moveToNextPoint();
            Toast.makeText(this, "地点" + routeManager.getCurrentRoutePoint() + "に到達しました", Toast.LENGTH_SHORT).show();
        }

        // 到達地点でのアドバタイズ実行
        routeManager.executeAdvertise();
        recordRouteEvent("ADVERTISE");

        // 🆕 ADVERTISE時のデータを保存（STOP用）
        lastAdvertiseData = pdrService.getCurrentData();

        if (bleFlag) {
            single400msBLEAdvertise((byte) 0xBE);
        }

        updateMainButton();
        updateStatusBar();
    }

    /**
     * Foreground Service起動
     */
    private void startForegroundService() {
        Intent serviceIntent = new Intent(this, PDRForegroundService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Service接続
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        Log.d(TAG, "Foreground service started");
    }

    /**
     * 測定完了処理
     */
    private void completeTracking() {
        // BLE終了信号のみ送信
        if (bleFlag) {
            // single400msBLEAdvertise((byte) 0xBE);
        }

        // 🆕 測定停止（最後のADVERTISE時点のデータを使用、Distance=0）
        if (lastAdvertiseData != null) {
            RouteManager.RoutePoint targetPoint = routeManager.getCurrentTargetPoint();
            if (targetPoint != null) {
                // 最後のADVERTISE時点の座標を使用、Distance=0を明示的に指定
                pdrService.writeRouteEvent("STOP", lastAdvertiseData, routeManager.getCurrentTrialNumber(),
                        routeManager.getCurrentRoutePoint(), targetPoint.getX(), targetPoint.getY(), 0.0);
            }
        }

        pdrService.stop();
        routeManager.stopMeasurement();

        // PDRサービスのリセット
        pdrService.reset();

        // 🆕 保存データもクリア
        lastAdvertiseData = null;

        // Trial番号インクリメント
        RouteManager.RoutePreset currentRoute = routeManager.getCurrentRoute();
        if (currentRoute != null) {
            routeManager.incrementTrialNumber(currentRoute.getRouteId());
            Toast.makeText(this, "Trial完了！次回はTrial " +
                            String.format("%02d", routeManager.getCurrentTrialNumber()) + "です",
                    Toast.LENGTH_LONG).show();
        }

        if (isTracking) {
            isTracking = false;
        }

        // Foreground Service停止
        stopForegroundService();

        btnReset.setEnabled(false);
        spinnerRoute.setEnabled(true);
        handler.removeCallbacks(updateRunnable);
        updateMainButton();
        updateStatusBar();
    }

    /**
     * Foreground Service停止
     */
    private void stopForegroundService() {
        // Service切断
        if (serviceBound) {
            try {
                unbindService(serviceConnection);
                serviceBound = false;
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service", e);
            }
        }

        // Service停止
        Intent serviceIntent = new Intent(this, PDRForegroundService.class);
        stopService(serviceIntent);

        Log.d(TAG, "Foreground service stopped");
    }

    private void resetTracking() {
        if (isTracking) {
            isTracking = false;
        }

        pdrService.reset();
        routeManager.resetCurrentTrial(); // Trial番号はそのまま

        // 🆕 保存データもクリア
        lastAdvertiseData = null;

        // Foreground Service停止
        stopForegroundService();

        btnReset.setEnabled(false);
        spinnerRoute.setEnabled(true);
        resetUI();
        updateMainButton();
        updateStatusBar();
        handler.removeCallbacks(updateRunnable);

        Toast.makeText(this, "リセットしました。同じTrialで再測定できます。", Toast.LENGTH_SHORT).show();
    }

    private void single400msBLEAdvertise(byte flag) {
        startBLEAdvertising(flag);
        handler.postDelayed(() -> stopBLEAdvertising(), 400);
    }

    private void finishBLEAdvertising() {
        if (bleFlag) {
            btnBLEfinish.setEnabled(false);
            btnMainAction.setEnabled(false);
            btnReset.setEnabled(true);

            startBLEAdvertising((byte)0xFF);
            Toast.makeText(this, "Sending BLE finish signal", Toast.LENGTH_SHORT).show();

            handler.postDelayed(new Runnable() {
                private int count = 0;

                @Override
                public void run() {
                    stopBLEAdvertising();
                    startBLEAdvertising((byte)0xFF);
                    count++;

                    if (count < 5) {
                        handler.postDelayed(this, 500);
                    } else {
                        handler.postDelayed(() -> {
                            stopBLEAdvertising();
                            btnBLEfinish.setEnabled(true);
                            Toast.makeText(MainActivity.this, "BLE finish signal completed", Toast.LENGTH_SHORT).show();
                        }, 500);
                    }
                }
            }, 500);
        }
    }

    // =========================== UI更新 ===========================

    /**
     * 上部ステータスバーの更新
     */
    private void updateStatusBar() {
        // ルート情報
        RouteManager.RoutePreset currentRoute = routeManager.getCurrentRoute();
        if (currentRoute != null) {
            tvRouteInfo.setText(currentRoute.getRouteName());
        } else {
            RouteManager.RoutePreset selectedRoute = (RouteManager.RoutePreset) spinnerRoute.getSelectedItem();
            tvRouteInfo.setText(selectedRoute != null ? selectedRoute.getRouteName() : "未選択");
        }

        // Trial番号
        tvTrialNumber.setText(routeManager.getTrialText());

        // 地点情報
        tvCurrentPoint.setText(routeManager.getCurrentRouteProgressText());
    }

    private void updateUI() {
        int steps = pdrService.getStepCount();
        double distance = pdrService.getDistance();

        tvStepCount.setText(String.format("%d", steps));
        tvDistance.setText(String.format("%.2f m", distance));
        tvHeading.setText(String.format("%.1f°", Math.toDegrees(pdrService.getHeading())));

        updateMainButton();
        updateStatusBar();
    }

    private void resetUI() {
        // レイアウトに存在するTextViewのみ更新
        tvStepCount.setText("0");
        tvDistance.setText("0.00 m");
        tvHeading.setText("0.0°");
    }

    private void recordRouteEvent(String event) {
        PDRService.SensorData currentData = pdrService.getCurrentData();
        if (currentData != null) {
            RouteManager.RoutePoint targetPoint = routeManager.getCurrentTargetPoint();
            if (targetPoint != null) {
                pdrService.writeRouteEvent(event, currentData, routeManager.getCurrentTrialNumber(),
                        routeManager.getCurrentRoutePoint(), targetPoint.getX(), targetPoint.getY());
            } else {
                pdrService.writeRouteEvent(event, currentData, routeManager.getCurrentTrialNumber(),
                        0, 0.0f, 0.0f);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        Log.d(TAG, "onDestroy called");

        // Handler完全停止（メモリリーク対策）
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }

        // BLE停止
        if (bluetoothLeAdvertiser != null) {
            try {
                stopBLEAdvertising();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping BLE", e);
            }
            bluetoothLeAdvertiser = null;
        }

        // Service切断
        if (serviceBound) {
            try {
                unbindService(serviceConnection);
                serviceBound = false;
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service", e);
            }
        }

        // ルート保存
        if (routeManager != null) {
            routeManager.saveRoutes();
        }

        // リソース解放
        pdrService = null;
        routeManager = null;
        bluetoothAdapter = null;

        Log.d(TAG, "Resources released");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission granted");
                Toast.makeText(this, "通知権限が許可されました", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "Notification permission denied");
                Toast.makeText(this, "通知権限が必要です。バックグラウンド動作に影響があります。",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_BATTERY_OPTIMIZATION) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String packageName = getPackageName();

            if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(TAG, "Battery optimization exemption granted");
                Toast.makeText(this, "バッテリー最適化が除外されました", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "Battery optimization exemption denied");
                Toast.makeText(this, "バッテリー最適化除外が推奨されます", Toast.LENGTH_LONG).show();
            }
        }
    }
}