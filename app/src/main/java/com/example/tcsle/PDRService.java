package com.example.tcsle;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class PDRService implements SensorEventListener {
    private static final String TAG = "PDRService";
    private Context context;
    private long currentTime;
    private long startTime;
    private SensorData currentData;

    // ========== PDR関連定数 ==========
    private static final float NS2S = 1.0f / 1000000000.0f;
    private static final float kf = 0.8f;  // CF補正係数
    private static final float ke = 0.1f;  // エラー積分係数
    private static final float A = 10.0f;  // ピークドメイン閾値
    private static final float tmin = 0.25f; // 最小時間間隔
    private static final float tmax = 2.0f;  // 最大時間間隔
    private static final float K = 0.97f;  // Weinberg係数
    private static final float q_tcsle = 0.6f;  // 比例係数
    private static final float dt = 0.01f;  // 時間間隔

    // バイアス補正値
    private final float[] aBias = {0.1639f, 0.1739f, 0.0440f};
    private final float[] ωBias = {8.2161e-5f, -1.0239e-5f, -0.6398e-5f};

    // ========== センサー関連 ==========
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;

    private float[] a = new float[3];  // 加速度
    private float[] ω = new float[3];  // 角速度
    private float[] T = new float[9];  // 変換行列
    private float[] q = {1.0f, 0.0f, 0.0f, 0.0f};  // クォータニオン
    private float[] error_sum = {0.0f, 0.0f, 0.0f};

    private float[] li = new float[3];  // 歩幅履歴
    private float[] φi = new float[3];  // 方位角履歴

    private double Xk = 0.0;  // X座標
    private double Yk = 0.0;  // Y座標
    private double totalDistance = 0.0;

    private int stepCount = 0;
    private long lastStepTime = 0;
    private float ap;
    private float ap_max = 0;
    private float ap_min = 0;
    private boolean isStepDetecting = false;
    private boolean isPeakCounted = false;
    private int Z = 0;  // 歩行状態
    private int lastZ = 0;

    // ========== カルマンフィルタ内蔵クラス ==========
    private class KalmanFilter {
        private double P = 1.0;  // 推定誤差共分散
        private final double Q;  // プロセスノイズ共分散
        private final double R;  // 観測ノイズ共分散
        private double X = 0.0;  // 状態推定値

        public KalmanFilter(double Q, double R) {
            this.Q = Q;
            this.R = R;
        }

        public double update(double measurement) {
            double K = P / (P + R);
            X = X + K * (measurement - X);
            P = (1 - K) * P + Q;
            return X;
        }
    }

    private KalmanFilter[] accKF = new KalmanFilter[3];
    private KalmanFilter[] gyroKF = new KalmanFilter[3];
    private KalmanFilter stepLengthKF;

    // ========== CSV書き込み機能 ==========
    private FileWriter fileWriter;
    private FileWriter eventFileWriter;
    private File csvFile;
    private File eventFile;
    private double lasttotalDistance = 0.0;

    // ルート管理用
    private String routeId;
    private int trialNumber;
    private boolean isRouteMode = false;

    // ========== センサーデータクラス ==========
    public class SensorData {
        public long timestamp;
        public float[] acceleration;
        public float[] gyroscope;
        public int stepCount;
        public double x;
        public double y;
        public float heading;
        public double totalDistance;
        public float ap;
        public float stepLength;

        public SensorData(long time, float[] acc, float[] gyro, int steps,
                          double posX, double posY, float head, double dist, float accMag) {
            this.timestamp = time;
            this.acceleration = acc.clone();
            this.gyroscope = gyro.clone();
            this.stepCount = steps;
            this.x = posX;
            this.y = posY;
            this.heading = head;
            this.totalDistance = dist;
            this.ap = accMag;
            this.stepLength = (steps > 0) ? (float)(dist / steps) : 0.0f;
        }
    }

    public PDRService(Context context) {
        this.context = context;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        // カルマンフィルタの初期化
        for (int i = 0; i < 3; i++) {
            accKF[i] = new KalmanFilter(0.001, 0.1);
            gyroKF[i] = new KalmanFilter(0.001, 0.1);
        }
        stepLengthKF = new KalmanFilter(0.001, 0.1);
    }

    // ========== ルート情報設定 ==========
    public void setRouteInfo(String routeId, int trialNumber) {
        this.routeId = routeId;
        this.trialNumber = trialNumber;
        this.isRouteMode = true;
        Log.i(TAG, "Route info set: " + routeId + " Trial " + trialNumber);
    }

    // ========== センサー処理 ==========
    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                processAccelerometer(event.values);
                detectStep();
                writeSensorData();
                break;
            case Sensor.TYPE_GYROSCOPE:
                processGyroscope(event.values);
                complementaryFilter();
                updateQuaternion();
                updateOrientation();
                writeSensorData();
                break;
        }
    }

    private void processAccelerometer(float[] values) {
        for (int i = 0; i < 3; i++) {
            float aRaw = values[i] - aBias[i];
            a[i] = (float) accKF[i].update(aRaw);
        }
    }

    private void processGyroscope(float[] values) {
        for (int i = 0; i < 3; i++) {
            float ωRaw = 2 * (values[i] - ωBias[i]);
            ω[i] = (float) gyroKF[i].update(ωRaw);
        }
    }

    private void detectStep() {
        ap = (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);

        if (ap > A) {
            if(!isStepDetecting) {
                isStepDetecting = true;
                ap_max = ap;
                ap_min = ap;
            }

            if (isStepDetecting && ap < ap_max && !isPeakCounted) {
                long currentTime = System.nanoTime();
                float Δt = (currentTime - lastStepTime) * NS2S;
                if (tmin < Δt && Δt < tmax) {
                    stepCount++;
                    float l = estimateStepLength(ap_max, ap_min);
                    updatePosition(l);
                    lastStepTime = currentTime;
                    isPeakCounted = true;
                }
            }

            ap_max = Math.max(ap_max, ap);
            ap_min = Math.min(ap_min, ap);
        } else {
            isStepDetecting = false;
            isPeakCounted = false;
        }
    }

    private float estimateStepLength(float amax, float amin) {
        float l = K * (float) Math.pow(amax - amin, 0.25);
        float l_prev = (li[0] + li[1] + li[2]) / 3.0f;

        System.arraycopy(li, 1, li, 0, 2);
        li[2] = l;

        float L = q_tcsle * l_prev + (1 - q_tcsle) * l;
        return (float) stepLengthKF.update(L);
    }

    private void complementaryFilter() {
        float q0 = q[0], q1 = q[1], q2 = q[2], q3 = q[3];
        float a0 = a[0], a1 = a[1], a2 = a[2];

        float norm = (float) Math.sqrt(a0 * a0 + a1 * a1 + a2 * a2);
        if (norm > 0) {
            a0 /= norm;
            a1 /= norm;
            a2 /= norm;
        }

        float[] current_error = {(-a2 * 2 * (q2 * q3 + q0 * q1) + a1 * (1 - 2 * (q1 * q1 + q2 * q2))),
                (a2 * 2 * (q1 * q3 - q0 * q2) + -a0 * (1 - 2 * (q1 * q1 + q2 * q2))),
                (-a1 * 2 * (q1 * q3 - q0 * q2) + a0 * 2 * (q2 * q3 + q0 * q1))};

        ω[0] += kf * current_error[0] + error_sum[0];
        ω[1] += kf * current_error[1] + error_sum[1];
        ω[2] += kf * current_error[2] + error_sum[2];

        error_sum[0] += ke * dt * current_error[0];
        error_sum[1] += ke * dt * current_error[1];
        error_sum[2] += ke * dt * current_error[2];
    }

    private void updateQuaternion() {
        float q0 = q[0], q1 = q[1], q2 = q[2], q3 = q[3];

        q[0] += dt/2 * (-q1*ω[0] - q2*ω[1] - q3*ω[2]);
        q[1] += dt/2 * (q0*ω[0] + q2*ω[2] - q3*ω[1]);
        q[2] += dt/2 * (q0*ω[1] - q1*ω[2] + q3*ω[0]);
        q[3] += dt/2 * (q0*ω[2] + q1*ω[1] - q2*ω[0]);

        float norm = (float) Math.sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]);
        for (int i = 0; i < 4; i++) {
            q[i] /= norm;
        }
    }

    private void updateOrientation() {
        T[0] = q[0]*q[0] + q[1]*q[1] - q[2]*q[2] - q[3]*q[3];
        T[1] = 2*(q[1]*q[2] - q[0]*q[3]);
        T[2] = 2*(q[1]*q[3] + q[0]*q[2]);
        T[3] = 2*(q[1]*q[2] + q[0]*q[3]);
        T[4] = q[0]*q[0] - q[1]*q[1] + q[2]*q[2] - q[3]*q[3];
        T[5] = 2*(q[2]*q[3] - q[0]*q[1]);
        T[6] = 2*(q[1]*q[3] - q[0]*q[2]);
        T[7] = 2*(q[2]*q[3] + q[0]*q[1]);
        T[8] = q[0]*q[0] - q[1]*q[1] - q[2]*q[2] + q[3]*q[3];

        float φ = (float) Math.atan2(T[1], T[4]);
        adaptiveDriftElimination(φ);
    }

    private void adaptiveDriftElimination(float φ) {
        if (stepCount < 3) {
            φi[2] = φ;
            return;
        }

        System.arraycopy(φi, 1, φi, 0, 2);

        float headingChange = (φ - φi[1]) + (φi[1] - φi[0]);
        lastZ = Z;
        Z = (Math.abs(headingChange) < Math.toRadians(20)) ? 0 : 1;

        if (Z == 1) {  // 旋回時
            float Δφ = φ % (float)(Math.PI/4);
            if (Δφ > Math.PI/8) {
                φ = φ - Δφ + (float)(Math.PI/4);
            } else {
                φ = φ - Δφ;
            }
        } else if (lastZ == 0 && Z == 0) {  // 直進時
            float Δφ = φ - (float)(Math.PI/8) * Math.round(φ/(Math.PI/8));
            φ = φ - Δφ - Δφ * (float)Math.sin(Δφ) * Math.round(φ/(Math.PI/8));
        } else if (lastZ == 1 && Z == 0) {  // 旋回から直進に変化
            float Δφ = φ % (float)(Math.PI/4);
            if (Δφ > Math.PI/8) {
                φ = φ - Δφ + (float)(Math.PI/4);
            } else {
                φ = φ - Δφ;
            }
        }
        φi[2] = φ;
    }

    private void updatePosition(float l) {
        Xk = Xk + l * Math.sin(φi[2]);
        Yk = Yk + l * Math.cos(φi[2]);
        totalDistance += l;
    }

    // ========== CSV書き込み機能 ==========

    private void openCSVFiles() {
        if (isRouteMode) {
            createRouteSensorFile();
            createRouteEventFile();
        } else {
            createLegacyFiles();
        }
    }

    private void createRouteSensorFile() {
        String fileName = generateSensorFileName();
        File directory = context.getExternalFilesDir(null);
        csvFile = new File(directory, fileName);

        try {
            boolean fileExists = csvFile.exists();
            fileWriter = new FileWriter(csvFile, true);

            if (!fileExists) {
                fileWriter.append("Time(ns),ax,ay,az,gx,gy,gz,StepCount,StepLength,X,Y,Heading,TotalDistance,ap,TrialID\n");
                Log.i(TAG, "Sensor file created: " + csvFile.getAbsolutePath());
            } else {
                Log.i(TAG, "Sensor file opened for append: " + csvFile.getAbsolutePath());
            }
            fileWriter.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error creating sensor file", e);
        }
    }

    private void createRouteEventFile() {
        String fileName = generateEventFileName();
        File directory = context.getExternalFilesDir(null);
        eventFile = new File(directory, fileName);

        try {
            boolean fileExists = eventFile.exists();
            eventFileWriter = new FileWriter(eventFile, true);

            if (!fileExists) {
                eventFileWriter.append("Time(ns),TrialID,Event,RoutePoint,TargetX,TargetY,EstimatedX,EstimatedY,Distance\n");
                Log.i(TAG, "Event file created: " + eventFile.getAbsolutePath());
            } else {
                Log.i(TAG, "Event file opened for append: " + eventFile.getAbsolutePath());
            }
            eventFileWriter.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error creating event file", e);
        }
    }

    private void createLegacyFiles() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String timestamp = sdf.format(new Date());

        // センサーファイル
        String fileName = timestamp + ".csv";
        File directory = context.getExternalFilesDir(null);
        csvFile = new File(directory, fileName);

        try {
            fileWriter = new FileWriter(csvFile);
            fileWriter.append("Time(ns),ax,ay,az,gx,gy,gz,StepCount,StepLength,X,Y,Heading,TotalDistance,ap\n");
            fileWriter.flush();
            Log.i(TAG, "CSV file created: " + csvFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error creating CSV file", e);
        }

        // イベントファイル
        String eventFileName = timestamp + "_events.csv";
        eventFile = new File(directory, eventFileName);

        try {
            eventFileWriter = new FileWriter(eventFile);
            eventFileWriter.append("Time(ns),Event,Distance\n");
            eventFileWriter.flush();
            Log.i(TAG, "Event file created: " + eventFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error creating event file", e);
        }
    }

    private String generateSensorFileName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        String date = sdf.format(new Date());
        return routeId + "_" + date + "_sensor.csv";
    }

    private String generateEventFileName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        String date = sdf.format(new Date());
        return routeId + "_" + date + "_events.csv";
    }

    private void writeSensorData() {
        if (fileWriter == null) return;

        currentTime = System.nanoTime() - startTime;
        currentData = new SensorData(
                currentTime, a.clone(), ω.clone(), stepCount,
                Xk, Yk, φi[2], totalDistance, ap
        );

        try {
            String line;
            if (isRouteMode) {
                line = String.format(Locale.US,
                        "%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,Trial%02d\n",
                        currentData.timestamp,
                        currentData.acceleration[0], currentData.acceleration[1], currentData.acceleration[2],
                        currentData.gyroscope[0], currentData.gyroscope[1], currentData.gyroscope[2],
                        currentData.stepCount, currentData.stepLength,
                        currentData.x, currentData.y, Math.toDegrees(currentData.heading),
                        currentData.totalDistance, currentData.ap, trialNumber
                );
            } else {
                line = String.format(Locale.US,
                        "%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f\n",
                        currentData.timestamp,
                        currentData.acceleration[0], currentData.acceleration[1], currentData.acceleration[2],
                        currentData.gyroscope[0], currentData.gyroscope[1], currentData.gyroscope[2],
                        currentData.stepCount, currentData.stepLength,
                        currentData.x, currentData.y, Math.toDegrees(currentData.heading),
                        currentData.totalDistance, currentData.ap
                );
            }

            fileWriter.append(line);
            fileWriter.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error writing to CSV file", e);
        }
    }

    public void writeRouteEvent(String event, SensorData data, int trialNumber,
                                int routePoint, float targetX, float targetY) {
        if (eventFileWriter == null || !isRouteMode) return;

        try {
            double distance = data.totalDistance - lasttotalDistance;
            lasttotalDistance = data.totalDistance;

            String line = String.format(Locale.US,
                    "%d,Trial%02d,%s,%d,%.1f,%.1f,%.3f,%.3f,%.3f\n",
                    data.timestamp, trialNumber, event, routePoint,
                    targetX, targetY, data.x, data.y, distance
            );

            eventFileWriter.append(line);
            eventFileWriter.flush();
            Log.d(TAG, "Route event recorded: " + event + " at point " + routePoint);
        } catch (IOException e) {
            Log.e(TAG, "Error writing to route event file", e);
        }
    }

    // ========== システム制御 ==========

    public void start() {
        openCSVFiles();
        startTime = System.nanoTime();
        lastStepTime = System.nanoTime();

        currentData = new SensorData(0, new float[]{0, 0, 0}, new float[]{0, 0, 0},
                stepCount, Xk, Yk, φi[2], totalDistance, ap);
        writeSensorData();

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
    }

    public void stop() {
        closeCSVFiles();
        sensorManager.unregisterListener(this);
    }

    public void reset() {
        Xk = 0.0; Yk = 0.0; totalDistance = 0.0; stepCount = 0;
        lastStepTime = System.nanoTime();
        Arrays.fill(li, 0f); Arrays.fill(φi, 0f);
        ap_max = 0; ap_min = 0; isStepDetecting = false;
        q = new float[]{1.0f, 0.0f, 0.0f, 0.0f};
        Z = 0; lasttotalDistance = 0.0; isRouteMode = false;
        routeId = null; trialNumber = 0;
    }

    private void closeCSVFiles() {
        if (fileWriter != null) {
            try {
                fileWriter.close();
                Log.i(TAG, "CSV file closed");
            } catch (IOException e) {
                Log.e(TAG, "Error closing CSV file", e);
            }
        }
        if (eventFileWriter != null) {
            try {
                eventFileWriter.close();
                Log.i(TAG, "Event file closed");
            } catch (IOException e) {
                Log.e(TAG, "Error closing event file", e);
            }
        }
    }

    // ========== Getterメソッド ==========

    public double getX() { return Xk; }
    public double getY() { return Yk; }
    public double getDistance() { return totalDistance; }
    public int getStepCount() { return stepCount; }
    public float getHeading() { return φi[2]; }
    public float[] getAcceleration() { return a; }
    public float[] getGyroscope() { return ω; }
    public float getAp() { return ap; }
    public SensorData getCurrentData() { return currentData; }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}