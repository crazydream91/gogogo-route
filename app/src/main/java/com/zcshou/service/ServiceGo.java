package com.zcshou.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.joystick.JoyStick;
import com.zcshou.utils.RoutePilot;

import java.util.List;

public class ServiceGo extends Service {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    /* 稳住后台：唤醒锁 + 通知里的实时进度 */
    private PowerManager.WakeLock mWakeLock = null;
    private double mCruiseDist = 0.0;
    private int mNoteTick = 0;
    private boolean mFusedOk = false;
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    // 摇杆相关
    private JoyStick mJoyStick;

    // ============ 自动巡航 + 路线记录 ============
    private final RoutePilot mPilot = new RoutePilot();
    private boolean mAuto = false;
    private boolean mRecording = false;
    private final StringBuilder mRecBuf = new StringBuilder();
    private int mRecCount = 0;
    private double mRecLastLat = 0.0;
    private double mRecLastLng = 0.0;
    private double mRecSpeedSum = 0.0;
    private int mRecSpeedN = 0;
    private double mRecSpeedAvg = 0.0;
    private static final double REC_MIN_DIST = 2.0;   /* 相邻记录点最小间距（米） */

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        removeTestProviderNetwork();
        addTestProviderNetwork();

        removeTestProviderGPS();
        addTestProviderGPS();
        addTestProviderFused();

        initGoLocation();

        initNotification();
        acquireWakeLock();

        initJoyStick();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mCurLng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
        mCurLat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
        mCurAlt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);

        mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        isStop = true;
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        mLocHandlerThread.quit();

        mJoyStick.destroy();

        removeTestProviderNetwork();
        removeTestProviderGPS();

        unregisterReceiver(mActReceiver);
        stopForeground(STOP_FOREGROUND_REMOVE);
        releaseWakeLock();

        super.onDestroy();
    }

    private void initNotification() {
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        registerReceiver(mActReceiver, filter);

        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        Notification notification = buildNotification(getResources().getString(R.string.app_service_tips));
        startForeground(SERVICE_GO_NOTE_ID, notification);
    }

    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                mSpeed = speed;
                // 根据当前的经纬度和距离，计算下一个经纬度
                // Latitude: 1 deg = 110.574 km // 纬度的每度的距离大约为 110.574km
                // Longitude: 1 deg = 111.320*cos(latitude) km  // 经度的每度的距离从0km到111km不等
                // 具体见：http://wp.mlab.tw/?p=2200
                mCurLng += disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180));
                mCurLat += disLat / 110.574;
                mCurBea = (float) angle;
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
            }
        });
        mJoyStick.show();
    }

    /** 巡航推进一帧（100 ms） */
    /** 巡航推进一帧（100 ms）：走摇杆通道，不和手动抢坐标 */
    private void advanceAuto() {
        // 用户正在亲手操作摇杆 → 这一帧让位（松手 1.5 秒后巡航自动接上）
        if (mJoyStick != null && mJoyStick.isUserActive()) {
            return;
        }
        double[] p = mPilot.step(mCurLat, mCurLng, 0.1);
        if (p == null) {
            mAuto = false;
            XLog.i("SERVICEGO: auto route finished");
            return;
        }
        double bearing = p[2];
        double speed = mPilot.getSpeed();
        if (mJoyStick != null) {
            // 与手动同一条通道：JoyStick.onMoveInfo 负责更新 mCurLng/mCurLat/mCurBea/mSpeed
            mJoyStick.pulseDrive(speed, bearing, 0.1);
            mCruiseDist += speed * 0.1;
        } else {
            mCruiseDist += RoutePilot.distance(mCurLat, mCurLng, p[0], p[1]);
            mCurLat = p[0];
            mCurLng = p[1];
            mCurBea = (float) bearing;
            mSpeed = speed;
        }
    }

    /** 记录当前点（间距 >= 2 米才记） */
    private void recordTick() {
        if (mRecCount > 0 && RoutePilot.distance(mRecLastLat, mRecLastLng, mCurLat, mCurLng) < REC_MIN_DIST) {
            return;
        }
        if (mRecCount >= 20000) {
            return;
        }
        if (mRecBuf.length() > 0) {
            mRecBuf.append(';');
        }
        mRecBuf.append(mCurLat).append(',').append(mCurLng);
        mRecCount++;
        mRecLastLat = mCurLat;
        mRecLastLng = mCurLng;
        mRecSpeedSum += mSpeed;
        mRecSpeedN++;
    }

    private void initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(100);

                    if (!isStop) {
                        if (mAuto) {
                            advanceAuto();
                        }
                        if (mRecording) {
                            recordTick();
                        }
                        setLocationNetwork();
                        setLocationGPS();
                        if (mFusedOk) {
                            setLocationFused();
                        }
                        mNoteTick++;
                        if (mNoteTick >= 10) {
                            mNoteTick = 0;
                            updateNote();
                        }

                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage");
                    Thread.currentThread().interrupt();
                }
            }
        };

        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
    }

    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    private void setLocationGPS() {
        try {
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_FINE);    // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 7);
            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS");
        }
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    private void setLocationNetwork() {
        try {
            // 尽可能模拟真实的 NETWORK 数据
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_COARSE);  // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork");
        }
    }

    /* ---------------- 后台稳定性：唤醒锁 / 通知进度 / fused 注入 ---------------- */

    private void acquireWakeLock() {
        try {
            if (mWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoGo:ServiceGo");
                    mWakeLock.setReferenceCounted(false);
                }
            }
            if (mWakeLock != null && !mWakeLock.isHeld()) {
                mWakeLock.acquire();
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - acquireWakeLock");
        }
    }

    private void releaseWakeLock() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - releaseWakeLock");
        }
    }

    /** 通知里显示实时进度，同时用于确认后台没被冻结 */
    private void updateNote() {
        try {
            String text;
            if (mAuto) {
                text = String.format(java.util.Locale.US, "巡航中 · %.1f m/s · 已走 %.0f 米",
                        mSpeed, mCruiseDist);
            } else if (mRecording) {
                text = String.format(java.util.Locale.US, "记录路线中 · 已存 %d 点", mRecCount);
            } else {
                text = getResources().getString(R.string.app_service_tips);
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(SERVICE_GO_NOTE_ID, buildNotification(text));
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - updateNote");
        }
    }

    private Notification buildNotification(String text) {
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(clickPI)
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_show), showPendingPI))
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_hide), hidePendingPI))
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    /** 有些导航 App 读 FUSED_PROVIDER，尽量也注入一份 */
    private void addTestProviderFused() {
        try {
            List<String> names = mLocManager.getAllProviders();
            if (names == null || !names.contains(LocationManager.FUSED_PROVIDER)) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.FUSED_PROVIDER, false, true,
                        false, true, true, true, true,
                        ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.FUSED_PROVIDER, false, true,
                        false, true, true, true, true,
                        Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.FUSED_PROVIDER, true);
            }
            mFusedOk = true;
        } catch (Exception e) {
            mFusedOk = false;
            XLog.e("SERVICEGO: ERROR - addTestProviderFused");
        }
    }

    private void setLocationFused() {
        try {
            Location loc = new Location(LocationManager.FUSED_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_FINE);
            loc.setAltitude(mCurAlt);
            loc.setBearing(mCurBea);
            loc.setLatitude(mCurLat);
            loc.setLongitude(mCurLng);
            loc.setTime(System.currentTimeMillis());
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            mLocManager.setTestProviderLocation(LocationManager.FUSED_PROVIDER, loc);
        } catch (Exception e) {
            mFusedOk = false;
            XLog.e("SERVICEGO: ERROR - setLocationFused");
        }
    }

    public class NoteActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    mJoyStick.show();
                }

                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    mJoyStick.hide();
                }
            }
        }
    }

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }

        public double getLat() {
            return mCurLat;
        }

        public double getLng() {
            return mCurLng;
        }

        /** 开始自动巡航；points 形如 "lat,lng;lat,lng" */
        public boolean startAutoRoute(String points, double speed, boolean loop) {
            List<RoutePilot.Pt> pts = RoutePilot.parse(points);
            if (pts.size() < 2) {
                return false;
            }
            mPilot.setRoute(pts, loop);
            mPilot.setSpeed(speed);
            mCruiseDist = 0.0;
            if (mJoyStick != null) {
                mJoyStick.cancelManualMove();
            }
            mAuto = true;
            return true;
        }

        public void stopAutoRoute() {
            mAuto = false;
            mPilot.stop();
        }

        public boolean isAutoRoute() {
            return mAuto;
        }

        public void setAutoSpeed(double speed) {
            mPilot.setSpeed(speed);
        }

        public void startRecordRoute() {
            mRecBuf.setLength(0);
            mRecCount = 0;
            mRecLastLat = 0.0;
            mRecLastLng = 0.0;
            mRecSpeedSum = 0.0;
            mRecSpeedN = 0;
            mRecSpeedAvg = 0.0;
            mRecording = true;
        }

        /** 停止记录并返回 "lat,lng;..." */
        public String stopRecordRoute() {
            mRecording = false;
            mRecSpeedAvg = (mRecSpeedN > 0) ? (mRecSpeedSum / mRecSpeedN) : 0.0;
            return mRecBuf.toString();
        }

        /** 刚录完这条路线的平均速度（m/s）；没录过返回 0 */
        public double recordedSpeed() {
            return mRecSpeedAvg;
        }

        public boolean isRecordingRoute() {
            return mRecording;
        }

        public int recordedCount() {
            return mRecCount;
        }
    }
}


