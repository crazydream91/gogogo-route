package com.zcshou.utils;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import com.elvishew.xlog.XLog;

/** 路线记忆：把录下来的路线存进 SQLite，可命名、复用、删除 */
public class RouteStore extends SQLiteOpenHelper {
    public static final String DB_NAME = "RouteStore.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE = "Route";
    private static final String C_ID = "ID";
    private static final String C_NAME = "NAME";
    private static final String C_POINTS = "POINTS";
    private static final String C_CREATED = "CREATED";
    private static final String C_UPDATED = "UPDATED";
    private static final String C_SPEED = "SPEED";

    /** 一条路线 */
    public static class Route {
        public long id;
        public String name;
        public String points;
        public long created;
        public long updated;
        /** 录制时的平均速度（m/s），巡航按它跑 */
        public double speed;

        public RoutePilot.Pt[] toArray() {
            List<RoutePilot.Pt> pts = RoutePilot.parse(points);
            return pts.toArray(new RoutePilot.Pt[0]);
        }

        public int pointCount() {
            return RoutePilot.parse(points).size();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public RouteStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("create table if not exists " + TABLE + " ("
                + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + C_NAME + " TEXT NOT NULL, "
                + C_POINTS + " TEXT NOT NULL, "
                + C_CREATED + " BIGINT NOT NULL, "
                + C_UPDATED + " BIGINT NOT NULL, "
                + C_SPEED + " REAL NOT NULL DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        /* v1 -> v2：只加一列，保留已有路线，不重建表 */
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + C_SPEED + " REAL NOT NULL DEFAULT 0");
            } catch (Exception e) {
                XLog.e("ROUTESTORE: upgrade error");
            }
        }
    }

    public long insert(String name, String points, double speed) {
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put(C_NAME, name);
        cv.put(C_POINTS, points);
        cv.put(C_SPEED, speed);
        cv.put(C_CREATED, now);
        cv.put(C_UPDATED, now);
        try {
            return getWritableDatabase().insert(TABLE, null, cv);
        } catch (Exception e) {
            XLog.e("ROUTESTORE: insert error");
            return -1;
        }
    }

    public void rename(long id, String name) {
        ContentValues cv = new ContentValues();
        cv.put(C_NAME, name);
        cv.put(C_UPDATED, System.currentTimeMillis());
        try {
            getWritableDatabase().update(TABLE, cv, C_ID + " = ?", new String[] {String.valueOf(id)});
        } catch (Exception e) {
            XLog.e("ROUTESTORE: rename error");
        }
    }

    public void delete(long id) {
        try {
            getWritableDatabase().delete(TABLE, C_ID + " = ?", new String[] {String.valueOf(id)});
        } catch (Exception e) {
            XLog.e("ROUTESTORE: delete error");
        }
    }

    public List<Route> list() {
        List<Route> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE, null, null, null, null, null, C_UPDATED + " DESC");
            while (c.moveToNext()) {
                Route r = new Route();
                r.id = c.getLong(c.getColumnIndexOrThrow(C_ID));
                r.name = c.getString(c.getColumnIndexOrThrow(C_NAME));
                r.points = c.getString(c.getColumnIndexOrThrow(C_POINTS));
                r.created = c.getLong(c.getColumnIndexOrThrow(C_CREATED));
                r.updated = c.getLong(c.getColumnIndexOrThrow(C_UPDATED));
                r.speed = c.getDouble(c.getColumnIndexOrThrow(C_SPEED));
                out.add(r);
            }
        } catch (Exception e) {
            XLog.e("ROUTESTORE: list error");
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }
}
