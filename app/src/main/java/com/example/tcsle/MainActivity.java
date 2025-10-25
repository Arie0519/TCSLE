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

    // BLE関連
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser bluetoothLeAdvertiser;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<Intent> resultLauncher;
    private byte advFlag;

    // UI要素
    private TextView tvAcceleration, tvGyroscope, tvStepCount, tvStepLength;
    private TextView tvDistance, tvxPosition, tvyPosition, tvHeading;
    private Button btnStart, btnStop, btnReset, btnAdvertise, btnBLEfinish;
    private MaterialSwitch switchBLE;
    private Spinner spinnerRoute;
    private TextView tvCurrentPoint, tvTargetCoord, tvAdvertiseCount, tvTrialNumber, tvMeasurementStatus;

    // 状態管理
    public Handler handler;
    private Runnable updateRunnable;
    private boolean bleFlag = false;
    private boolean isTracking = false;
    private ArrayAdapter<RouteManager.RoutePreset> routeAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeServices();
        initializeViews();
        setupBLE();
        setupRouteUI();
        setupButtons();
        setupHandler();
        updateRouteUI();
    }

    private void initializeServices() {
        routeManager = new RouteManager(this);
        pdrService = new PDRService(this);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    private void initializeViews() {
        // センサーデータ表示用
        tvAcceleration = findViewById(R.id.accelerometerTextView);
        tvGyroscope = findViewById(R.id.gyroscopeTextView);
        tvStepCount = findViewById(R.id.stepCountTextView);
        tvStepLength = findViewById(R.id.stepLengthTextView);
        tvDistance = findViewById(R.id.distanceTextView);
        tvxPosition = findViewById(R.id.xpositionTextView);
        tvyPosition = findViewById(R.id.ypositionTextView);
        tvHeading = findViewById(R.id.headingTextView);

        // ボタン類
        btnStart = findViewById(R.id.startButton);
        btnStop = findViewById(R.id.stopButton);
        btnReset = findViewById(R.id.resetButton);
        btnAdvertise = findViewById(R.id.advertiseButton);
        btnBLEfinish = findViewById(R.id.finishButton);
        switchBLE = findViewById(R.id.switchBLE);

        // ルート管理用
        spinnerRoute = findViewById(R.id.spinnerRoute);
        tvCurrentPoint = findViewById(R.id.tvCurrentPoint);
        tvTargetCoord = findViewById(R.id.tvTargetCoord);
        tvAdvertiseCount = findViewById(R.id.tvAdvertiseCount);
        tvTrialNumber = findViewById(R.id.tvTrialNumber);
        tvMeasurementStatus = findViewById(R.id.tvMeasurementStatus);

        btnStop.setEnabled(false);
        switchBLE.setChecked(false);

        // ボタンテキストの設定
        btnStart.setText("測定開始");
        btnStop.setText("測定完了");
        btnAdvertise.setText("中間地点通過");
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
                    updateRouteUI();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupButtons() {
        btnStart.setOnClickListener(v -> startTracking());
        btnStop.setOnClickListener(v -> stopTracking());
        btnReset.setOnClickListener(v -> resetTracking());
        btnAdvertise.setOnClickListener(v -> advertise());
        btnBLEfinish.setOnClickListener(v -> finishBLEAdvertising());

        switchBLE.setOnCheckedChangeListener((buttonView, isChecked) -> {
            bleFlag = isChecked;
            btnBLEfinish.setEnabled(isChecked);
        });
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
        ByteBuffer buffer = ByteBuffer.allocate(10);
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
            pos_x = (int) Math.round(pdrService.getX() * 10);
            pos_y = (int) Math.round(pdrService.getY() * 10);
            pos_x = Math.max(-128, Math.min(127, pos_x));
            pos_y = Math.max(-128, Math.min(127, pos_y));
        }

        buffer.put((byte) pos_x);
        buffer.put((byte) pos_y);
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
            routeManager.startMeasurement(selectedRoute.getRouteId());
            pdrService.setRouteInfo(selectedRoute.getRouteId(), routeManager.getCurrentTrialNumber());
            isTracking = true;
        }

        pdrService.start();
        recordRouteEvent("START");

        if (bleFlag) {
            single400msBLEAdvertise((byte) 0xBE);
        }

        updateButtonStates(true);
        handler.post(updateRunnable);
        updateRouteUI();
    }

    private void stopTracking() {
        recordRouteEvent("STOP");
        pdrService.stop();
        routeManager.stopMeasurement();

        if (bleFlag) {
            single400msBLEAdvertise((byte) 0xBE);
        }

        if (isTracking) {
            isTracking = false;
        }

        updateButtonStates(false);
        handler.removeCallbacks(updateRunnable);
        updateRouteUI();
    }

    private void resetTracking() {
        if (isTracking) {
            isTracking = false;
        }

        pdrService.reset();
        routeManager.resetMeasurementState();

        updateButtonStates(false);
        resetUI();
        updateRouteUI();
        handler.removeCallbacks(updateRunnable);
    }

    private void advertise() {
        if (!routeManager.isMeasuring()) {
            Toast.makeText(this, "測定を開始してください", Toast.LENGTH_SHORT).show();
            return;
        }

        // 現在地点でのアドバタイズ実行
        routeManager.executeAdvertise();
        recordRouteEvent("ADVERTISE");

        if (bleFlag) {
            single400msBLEAdvertise((byte) 0xBE);
        }

        // 最終地点でなければ自動的に次の地点へ進行
        if (!routeManager.isLastPoint()) {
            routeManager.moveToNextPoint();
            recordRouteEvent("NEXT_POINT");
            Toast.makeText(this, "次の地点に進行しました", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "最終地点に到達しました", Toast.LENGTH_SHORT).show();
        }

        updateRouteUI();
    }

    private void moveToNextPoint() {
        if (!routeManager.isMeasuring()) {
            Toast.makeText(this, "測定中ではありません", Toast.LENGTH_SHORT).show();
            return;
        }

        if (routeManager.isLastPoint()) {
            Toast.makeText(this, "最終地点です", Toast.LENGTH_SHORT).show();
            return;
        }

        routeManager.moveToNextPoint();
        recordRouteEvent("NEXT_POINT");
        updateRouteUI();
        Toast.makeText(this, "次の地点に移動しました", Toast.LENGTH_SHORT).show();
    }

    private void single400msBLEAdvertise(byte flag) {
        startBLEAdvertising(flag);
        handler.postDelayed(() -> stopBLEAdvertising(), 400);
    }

    private void finishBLEAdvertising() {
        if (bleFlag) {
            btnBLEfinish.setEnabled(false);
            btnStart.setEnabled(false);
            btnStop.setEnabled(false);
            btnAdvertise.setEnabled(false);

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
                            updateButtonStates(isTracking);
                            Toast.makeText(MainActivity.this, "BLE finish signal completed", Toast.LENGTH_SHORT).show();
                        }, 500);
                    }
                }
            }, 500);
        }
    }

    // =========================== UI更新 ===========================

    private void updateUI() {
        float[] a = pdrService.getAcceleration();
        float[] ω = pdrService.getGyroscope();

        tvAcceleration.setText(String.format("ax: %.2f   ay: %.2f   az: %.2f", a[0], a[1], a[2]));
        tvGyroscope.setText(String.format("ωx: %.2f   ωy: %.2f   ωz: %.2f", ω[0], ω[1], ω[2]));

        int steps = pdrService.getStepCount();
        double distance = pdrService.getDistance();
        tvStepCount.setText(String.format("%d", steps));

        double stepLength = (steps > 0) ? (distance / steps) : 0.0;
        tvStepLength.setText(String.format("%.2f m", stepLength));

        tvDistance.setText(String.format("%.2f m", distance));
        tvxPosition.setText(String.format("X: %.2f m", pdrService.getX()));
        tvyPosition.setText(String.format("Y: %.2f m", pdrService.getY()));
        tvHeading.setText(String.format("%.1f°", Math.toDegrees(pdrService.getHeading())));

        updateRouteUI();
    }

    private void updateRouteUI() {
        tvCurrentPoint.setText("現在地点: " + routeManager.getCurrentRouteProgressText());

        RouteManager.RoutePoint targetPoint = routeManager.getCurrentTargetPoint();
        if (targetPoint != null) {
            tvTargetCoord.setText(String.format("目標座標: (%.1f, %.1f)",
                    targetPoint.getX(), targetPoint.getY()));
        } else {
            tvTargetCoord.setText("目標座標: 未設定");
        }

        tvAdvertiseCount.setText("アドバタイズ: " + routeManager.getAdvertiseCountText());
        tvTrialNumber.setText("試行回数: " + routeManager.getTrialText());

        if (routeManager.isMeasuring()) {
            RouteManager.RoutePreset currentRoute = routeManager.getCurrentRoute();
            if (currentRoute != null) {
                tvMeasurementStatus.setText("測定中 - " + currentRoute.getRouteId() + " " +
                        routeManager.getTrialText());
            } else {
                tvMeasurementStatus.setText("測定中");
            }
        } else {
            tvMeasurementStatus.setText("待機中");
        }

        // アドバタイズボタンのテキスト更新
        if (routeManager.isMeasuring() && targetPoint != null) {
            if (routeManager.isLastPoint()) {
                btnAdvertise.setText("最終地点通過");
            } else {
                btnAdvertise.setText(String.format("地点%d通過",
                        routeManager.getCurrentRoutePoint()));
            }
        } else {
            btnAdvertise.setText("中間地点通過");
        }
    }

    private void updateButtonStates(boolean measuring) {
        btnStart.setEnabled(!measuring);
        btnAdvertise.setEnabled(measuring);
        btnStop.setEnabled(measuring);
        btnReset.setEnabled(!measuring);
        spinnerRoute.setEnabled(!measuring);
    }

    private void resetUI() {
        tvAcceleration.setText("ax: 0.00   ay: 0.00   az: 0.00");
        tvGyroscope.setText("ωx: 0.00   ωy: 0.00   ωz: 0.00");
        tvStepCount.setText("0");
        tvStepLength.setText("0.00 m");
        tvDistance.setText("0.00 m");
        tvxPosition.setText("X: 0.00 m");
        tvyPosition.setText("Y: 0.00 m");
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
        stopTracking();
        if (bluetoothLeAdvertiser != null) {
            stopBLEAdvertising();
        }
        routeManager.saveRoutes();
    }
}