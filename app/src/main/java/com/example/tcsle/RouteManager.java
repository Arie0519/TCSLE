package com.example.tcsle;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RouteManager {
    private static final String TAG = "RouteManager";
    private static final String PREFS_NAME = "route_preferences";
    private static final String ROUTES_KEY = "saved_routes";

    private Context context;
    private SharedPreferences preferences;
    private Gson gson;

    // 現在の測定状態
    private RoutePreset currentRoute;
    private int currentRoutePoint;
    private int advertiseCount;
    private int currentTrialNumber;
    private boolean isMeasuring;

    private static final String TRIAL_PREFS_KEY = "trial_numbers";
    private SharedPreferences trialPreferences;

    // 保存されたルート一覧
    private List<RoutePreset> savedRoutes;

    // ========== RoutePoint内蔵クラス ==========
    public static class RoutePoint {
        private float x;
        private float y;
        private int pointNumber;

        public RoutePoint(int pointNumber, float x, float y) {
            this.pointNumber = pointNumber;
            this.x = x;
            this.y = y;
        }

        // Getters
        public float getX() { return x; }
        public float getY() { return y; }
        public int getPointNumber() { return pointNumber; }

        // Setters
        public void setX(float x) { this.x = x; }
        public void setY(float y) { this.y = y; }
        public void setPointNumber(int pointNumber) { this.pointNumber = pointNumber; }

        @Override
        public String toString() {
            return String.format("地点%d (%.1f, %.1f)", pointNumber, x, y);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            RoutePoint that = (RoutePoint) obj;
            return Float.compare(that.x, x) == 0 &&
                    Float.compare(that.y, y) == 0 &&
                    pointNumber == that.pointNumber;
        }

        @Override
        public int hashCode() {
            int result = Float.hashCode(x);
            result = 31 * result + Float.hashCode(y);
            result = 31 * result + pointNumber;
            return result;
        }
    }

    // ========== RoutePreset内蔵クラス ==========
    public static class RoutePreset {
        private String routeId;
        private String routeName;
        private List<RoutePoint> routePoints;

        public RoutePreset(String routeId, String routeName) {
            this.routeId = routeId;
            this.routeName = routeName;
            this.routePoints = new ArrayList<>();
        }

        public RoutePreset(String routeId, String routeName, List<RoutePoint> routePoints) {
            this.routeId = routeId;
            this.routeName = routeName;
            this.routePoints = new ArrayList<>(routePoints);
        }

        // Getters
        public String getRouteId() { return routeId; }
        public String getRouteName() { return routeName; }
        public List<RoutePoint> getRoutePoints() { return new ArrayList<>(routePoints); }
        public int getRoutePointCount() { return routePoints.size(); }

        public RoutePoint getRoutePoint(int index) {
            if (index >= 0 && index < routePoints.size()) {
                return routePoints.get(index);
            }
            return null;
        }

        // Setters
        public void setRouteId(String routeId) { this.routeId = routeId; }
        public void setRouteName(String routeName) { this.routeName = routeName; }

        // Route point management
        public void addRoutePoint(RoutePoint point) {
            routePoints.add(point);
        }

        public void addRoutePoint(float x, float y) {
            int pointNumber = routePoints.size() + 1;
            routePoints.add(new RoutePoint(pointNumber, x, y));
        }

        public void removeRoutePoint(int index) {
            if (index >= 0 && index < routePoints.size()) {
                routePoints.remove(index);
                // 地点番号を再採番
                for (int i = 0; i < routePoints.size(); i++) {
                    routePoints.get(i).setPointNumber(i + 1);
                }
            }
        }

        public void clearRoutePoints() {
            routePoints.clear();
        }

        // Display methods
        public String getRouteDescription() {
            if (routePoints.isEmpty()) {
                return routeName + ": 地点未設定";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(routeName).append(": ");

            for (int i = 0; i < routePoints.size(); i++) {
                RoutePoint point = routePoints.get(i);
                sb.append(String.format("(%.1f,%.1f)", point.getX(), point.getY()));
                if (i < routePoints.size() - 1) {
                    sb.append("→");
                }
            }

            return sb.toString();
        }

        @Override
        public String toString() {
            return getRouteDescription();
        }

        // Validation
        public boolean isValid() {
            return routeId != null && !routeId.trim().isEmpty() &&
                    routeName != null && !routeName.trim().isEmpty() &&
                    routePoints.size() >= 2;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            RoutePreset that = (RoutePreset) obj;
            return routeId.equals(that.routeId);
        }

        @Override
        public int hashCode() {
            return routeId.hashCode();
        }
    }

    // ========== RouteManager本体 ==========

    public RouteManager(Context context) {
        this.context = context;
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // ★この1行を追加して一度実行すると、保存データが消えます
//        preferences.edit().clear().apply();

        this.trialPreferences = context.getSharedPreferences(TRIAL_PREFS_KEY, Context.MODE_PRIVATE);
        this.gson = new Gson();
        this.savedRoutes = new ArrayList<>();

        initializeDefaultRoutes();
        loadSavedRoutes();
        resetMeasurementState();
    }

    private void initializeDefaultRoutes() {
        savedRoutes.clear();

        // Route_1m
        RoutePreset r1 = new RoutePreset("Route_1m", "Route_1m");
        r1.addRoutePoint(0.0f, 1.0f);
        r1.addRoutePoint(1.0f, 1.0f);
        savedRoutes.add(r1);

        // Route_2m
        RoutePreset r2 = new RoutePreset("Route_2m", "Route_2m");
        r2.addRoutePoint(0.0f, 1.0f);
        r2.addRoutePoint(2.0f, 1.0f);
        savedRoutes.add(r2);

        // Route_3m
        RoutePreset r3 = new RoutePreset("Route_3m", "Route_3m");
        r3.addRoutePoint(0.0f, 1.0f);
        r3.addRoutePoint(3.0f, 1.0f);
        savedRoutes.add(r3);

        // Route_4m
        RoutePreset r4 = new RoutePreset("Route_4m", "Route_4m");
        r4.addRoutePoint(0.0f, 1.0f);
        r4.addRoutePoint(4.0f, 1.0f);
        savedRoutes.add(r4);

        // Route_5m
        RoutePreset r5 = new RoutePreset("Route_5m", "Route_5m");
        r5.addRoutePoint(0.0f, 1.0f);
        r5.addRoutePoint(5.0f, 1.0f);
        savedRoutes.add(r5);

        // Route_6m
        RoutePreset r6 = new RoutePreset("Route_6m", "Route_6m");
        r6.addRoutePoint(0.0f, 1.0f);
        r6.addRoutePoint(6.0f, 1.0f);
        savedRoutes.add(r6);

        // Route_7m
        RoutePreset r7 = new RoutePreset("Route_7m", "Route_7m");
        r7.addRoutePoint(0.0f, 1.0f);
        r7.addRoutePoint(7.0f, 1.0f);
        savedRoutes.add(r7);

        // Route_8m
        RoutePreset r8 = new RoutePreset("Route_8m", "Route_8m");
        r8.addRoutePoint(0.0f, 1.0f);
        r8.addRoutePoint(8.0f, 1.0f);
        savedRoutes.add(r8);
    }

    private void loadSavedRoutes() {
        String routesJson = preferences.getString(ROUTES_KEY, null);
        if (routesJson != null) {
            try {
                Type listType = new TypeToken<List<RoutePreset>>(){}.getType();
                List<RoutePreset> loadedRoutes = gson.fromJson(routesJson, listType);

                for (RoutePreset loadedRoute : loadedRoutes) {
                    if (!containsRoute(loadedRoute.getRouteId())) {
                        savedRoutes.add(loadedRoute);
                    }
                }

                Log.i(TAG, "Loaded " + loadedRoutes.size() + " routes from preferences");
            } catch (Exception e) {
                Log.e(TAG, "Error loading saved routes", e);
            }
        }
    }

    public void saveRoutes() {
        try {
            String routesJson = gson.toJson(savedRoutes);
            preferences.edit().putString(ROUTES_KEY, routesJson).apply();
            Log.i(TAG, "Saved " + savedRoutes.size() + " routes to preferences");
        } catch (Exception e) {
            Log.e(TAG, "Error saving routes", e);
        }
    }

    // ルート管理メソッド
    public List<RoutePreset> getAllRoutes() {
        return new ArrayList<>(savedRoutes);
    }

    public RoutePreset getRoute(String routeId) {
        for (RoutePreset route : savedRoutes) {
            if (route.getRouteId().equals(routeId)) {
                return route;
            }
        }
        return null;
    }

    public void addRoute(RoutePreset route) {
        if (!containsRoute(route.getRouteId())) {
            savedRoutes.add(route);
            saveRoutes();
        }
    }

    public void removeRoute(String routeId) {
        savedRoutes.removeIf(route -> route.getRouteId().equals(routeId));
        saveRoutes();
    }

    private boolean containsRoute(String routeId) {
        return savedRoutes.stream().anyMatch(route -> route.getRouteId().equals(routeId));
    }

    // 測定状態管理
    public void startMeasurement(String routeId) {
        currentRoute = getRoute(routeId);
        if (currentRoute != null && currentRoute.isValid()) {
            currentRoutePoint = 1;
            advertiseCount = 0;
            currentTrialNumber = getNextTrialNumber(routeId);
            isMeasuring = true;
            Log.i(TAG, "Started measurement for " + routeId + " Trial " + currentTrialNumber);
        } else {
            Log.e(TAG, "Cannot start measurement: invalid route " + routeId);
        }
    }

    public void stopMeasurement() {
        isMeasuring = false;
        Log.i(TAG, "Stopped measurement");
    }

    public void resetMeasurementState() {
        currentRoute = null;
        currentRoutePoint = 0;
        advertiseCount = 0;
        currentTrialNumber = 0;
        isMeasuring = false;
    }

    // 地点進行管理
    public void executeAdvertise() {
        if (isMeasuring && currentRoute != null) {
            advertiseCount++;
            Log.d(TAG, "Advertise executed at point " + currentRoutePoint + " (count: " + advertiseCount + ")");
        }
    }

    public void moveToNextPoint() {
        if (isMeasuring && currentRoute != null) {
            if (currentRoutePoint < currentRoute.getRoutePointCount()) {
                currentRoutePoint++;
                advertiseCount = 0;
                Log.d(TAG, "Moved to point " + currentRoutePoint);
            }
        }
    }

    public boolean isLastPoint() {
        return currentRoute != null && currentRoutePoint >= currentRoute.getRoutePointCount();
    }

    private int getNextTrialNumber(String routeId) {
        String key = getTrialKey(routeId);
        int trialNumber = trialPreferences.getInt(key, 1);
        Log.d(TAG, "Next trial number for " + routeId + ": " + trialNumber);
        return trialNumber;
    }

    /**
     * Trial番号管理用のキーを生成（ルートID + 日付）
     */
    private String getTrialKey(String routeId) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        String date = sdf.format(new Date());
        return routeId + "_" + date;
    }

    /**
     * Trial番号をインクリメント
     */
    public void incrementTrialNumber(String routeId) {
        String key = getTrialKey(routeId);
        int currentTrial = trialPreferences.getInt(key, 1);
        int nextTrial = currentTrial + 1;

        trialPreferences.edit().putInt(key, nextTrial).apply();
        Log.i(TAG, "Trial incremented: " + currentTrial + " -> " + nextTrial);
    }

    /**
     * Trial番号をリセット（現在のTrialをやり直す）
     */
    public void resetCurrentTrial() {
        // Trial番号は変更しない
        // 測定状態のみリセット
        resetMeasurementState();
        Log.i(TAG, "Current trial reset");
    }

    /**
     * 指定ルートのTrial番号を1にリセット
     */
    public void resetTrialNumber(String routeId) {
        String key = getTrialKey(routeId);
        trialPreferences.edit().putInt(key, 1).apply();
        Log.i(TAG, "Trial number reset to 1 for " + routeId);
    }

    public String generateFileName(String routeId) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        String date = sdf.format(new Date());
        int trialNumber = getCurrentTrialNumber();
        return routeId + "_" + date + "_Trial" + String.format(Locale.US, "%02d", trialNumber) + ".csv";
    }

    // ========== Getterメソッド ==========

    public RoutePreset getCurrentRoute() { return currentRoute; }
    public int getCurrentRoutePoint() { return currentRoutePoint; }
    public int getAdvertiseCount() { return advertiseCount; }
    public int getCurrentTrialNumber() { return currentTrialNumber; }
    public boolean isMeasuring() { return isMeasuring; }

    public RoutePoint getCurrentTargetPoint() {
        if (currentRoute != null && currentRoutePoint > 0 &&
                currentRoutePoint <= currentRoute.getRoutePointCount()) {
            return currentRoute.getRoutePoint(currentRoutePoint - 1);
        }
        return null;
    }

    public String getCurrentRouteProgressText() {
        if (currentRoute == null) {
            return "ルート未選択";
        }
        return String.format("地点%d/%d", currentRoutePoint, currentRoute.getRoutePointCount());
    }

    public String getAdvertiseCountText() {
        return String.format("%s回実行済み", advertiseCount);
    }

    public String getTrialText() {
        if (currentTrialNumber == 0) {
            return "測定準備中";
        }
        return String.format("Trial%02d", currentTrialNumber);
    }
}