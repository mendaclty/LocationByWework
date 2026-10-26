package org.gallonyin.weworkhk;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.tencent.mapsdk.raster.model.LatLng;
import com.tencent.mapsdk.raster.model.Marker;
import com.tencent.mapsdk.raster.model.MarkerOptions;
import com.tencent.tencentmap.mapsdk.map.MapView;
import com.tencent.tencentmap.mapsdk.map.TencentMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 宿主内嵌选点弹窗：腾讯地图选点 + 地名搜索。
 *
 * 腾讯栅格地图 SDK 为纯 Java 实现（模块 APK 无 native 库），可在宿主进程直接实例化；
 * MapView 使用模块包的 Context（createPackageContext），使 SDK 能读到模块 manifest
 * 里的 TencentMapSDK key。地图类实际由模块自身 classloader 提供（LSPosed 注入即
 * WeWork.class.getClassLoader），不存在跨 loader 类型冲突。
 *
 * 搜索用腾讯位置服务 WebService（place/v1/search），key 与地图相同。
 * 确认后发送既有 weworkdk_gps 广播：本进程与其它进程的 hook 接收器统一处理内存与持久化。
 */
final class WeworkLocationPicker {
    private static final String TAG = "WeWork";
    private static final String MODULE_PACKAGE = BuildConfig.APPLICATION_ID;
    // key 由 Gradle 注入（local.properties: tencent.map.key），仓库内不保存真实 key。
    private static final String MAP_KEY = BuildConfig.TENCENT_MAP_KEY;
    private static final String MAP_KEY_PLACEHOLDER = "YOUR_TENCENT_MAP_KEY";

    private final Context hostContext;
    private final Context mapContext;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Dialog dialog;
    private static Dialog activeDialog;
    private MapView mapView;
    private TencentMap tencentMap;
    private Marker marker;
    private ListView resultList;
    private TextView pickedView;
    private View resultsContainer;

    private double pickedLa;
    private double pickedLo;

    private WeworkLocationPicker(Context hostContext, Context mapContext) {
        this.hostContext = hostContext;
        this.mapContext = mapContext;
        WeWork current = WeWork.get();
        this.pickedLa = current != null ? current.gcjLatitude() : 22.807641983032227d;
        this.pickedLo = current != null ? current.gcjLongitude() : 113.20242309570312d;
    }

    /** 音量键入口：可见时不再重复弹；隐藏过的恢复显示；没有则新建。 */
    static void showOrRestore(Context host) {
        Dialog d = activeDialog;
        if (d != null) {
            try {
                d.show();
                return;
            } catch (Throwable t) {
                // 宿主 Activity 可能已重建，旧 dialog 的 token 失效，弃用重建
                try { d.dismiss(); } catch (Throwable ignored) {
                }
                activeDialog = null;
            }
        }
        show(host);
    }

    static boolean isShowing() {
        Dialog d = activeDialog;
        if (d == null) return false;
        try {
            return d.isShowing();
        } catch (Throwable t) {
            return false;
        }
    }

    static void hideByVolumeKey() {
        Dialog d = activeDialog;
        if (d == null) return;
        try {
            d.hide();
        } catch (Throwable ignored) {
        }
    }

    static void show(Context host) {
        try {
            Context moduleCtx = host.createPackageContext(MODULE_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            // createPackageContext 的包装 Context 在宿主进程里 getApplicationContext() 会返回 null
            // （模块的 Application 未实例化），地图 SDK 内部会拿它去 getSharedPreferences 导致 NPE；
            // 补一个非空实现，保持模块身份以便读取 manifest 里的地图 key
            Context mapCtx = new android.content.ContextWrapper(moduleCtx) {
                @Override
                public Context getApplicationContext() {
                    return this;
                }
            };
            new WeworkLocationPicker(host, mapCtx).build().show();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " picker show failed: " + t);
            Log.e(TAG, "picker show failed", t);
            try {
                Toast.makeText(host, "选点弹窗启动失败: " + t, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }

    private Dialog build() {
        float d = hostContext.getResources().getDisplayMetrics().density;
        dialog = new Dialog(hostContext);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(hostContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF151515);
        int pad = (int) (12 * d);
        root.setPadding(pad, pad, pad, pad);

        // 搜索行
        LinearLayout searchRow = new LinearLayout(hostContext);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText keyword = new EditText(hostContext);
        keyword.setHint("搜索地名，如：写字楼/小区/学校");
        keyword.setHintTextColor(0xFF888888);
        keyword.setTextColor(0xFFEEEEEE);
        keyword.setSingleLine(true);
        keyword.setTextSize(14);
        searchRow.addView(keyword, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button searchBtn = new Button(hostContext);
        searchBtn.setText("搜索");
        searchBtn.setTextSize(14);
        searchRow.addView(searchBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(searchRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 搜索结果列表
        resultList = new ListView(hostContext);
        resultList.setBackgroundColor(0xFF202020);
        resultsContainer = resultList;
        resultsContainer.setVisibility(View.GONE);
        LinearLayout.LayoutParams resultsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (200 * d));
        resultsLp.topMargin = (int) (6 * d);
        root.addView(resultsContainer, resultsLp);

        // 地图
        FrameLayout mapArea = new FrameLayout(hostContext);
        try {
            mapView = createMapView();
            mapArea.addView(mapView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " map create failed: " + t);
            TextView error = new TextView(hostContext);
            error.setText("地图加载失败: " + t);
            error.setTextColor(0xFFFF6666);
            error.setGravity(Gravity.CENTER);
            mapArea.addView(error, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mapLp.topMargin = (int) (6 * d);
        root.addView(mapArea, mapLp);

        // 底部：坐标 + 按钮
        LinearLayout bottom = new LinearLayout(hostContext);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        pickedView = new TextView(hostContext);
        pickedView.setTextColor(0xFFEEEEEE);
        pickedView.setTextSize(12);
        updatePickedText();
        bottom.addView(pickedView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button confirm = new Button(hostContext);
        confirm.setText("确认修改");
        confirm.setTextSize(14);
        bottom.addView(confirm, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button cancel = new Button(hostContext);
        cancel.setText("取消");
        cancel.setTextSize(14);
        bottom.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.topMargin = (int) (6 * d);
        root.addView(bottom, bottomLp);

        dialog.setContentView(root);
        activeDialog = dialog;
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        setupMap();
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch(keyword.getText().toString().trim());
            }
        });
        keyword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 0 && resultsContainer != null) resultsContainer.setVisibility(View.GONE);
            }
        });
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        return dialog;
    }

    private MapView createMapView() {
        try {
            return new MapView(mapContext);
        } catch (Throwable first) {
            Log.d(TAG, "MapView(Context) failed, trying (Context, AttributeSet)");
            return new MapView(mapContext, null);
        }
    }

    private void setupMap() {
        if (mapView == null) return;
        try {
            tencentMap = mapView.getMap();
            tencentMap.setCenter(new LatLng(pickedLa, pickedLo));
            tencentMap.setZoom(16);
            tencentMap.setOnMapClickListener(new TencentMap.OnMapClickListener() {
                @Override
                public void onMapClick(LatLng latLng) {
                    pick(latLng.getLatitude(), latLng.getLongitude());
                }
            });
            showMarker(pickedLa, pickedLo);
            mapView.onResume();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " map setup failed: " + t);
        }
    }

    private void showMarker(double la, double lo) {
        try {
            tencentMap.clearAllOverlays();
            marker = tencentMap.addMarker(new MarkerOptions()
                    .position(new LatLng(la, lo))
                    .title(la + ", " + lo)
                    .anchor(0.5f, 0.5f));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " marker failed: " + t);
        }
    }

    private void pick(double la, double lo) {
        pickedLa = la;
        pickedLo = lo;
        showMarker(la, lo);
        updatePickedText();
    }

    private void updatePickedText() {
        if (pickedView != null) {
            pickedView.setText(String.format(java.util.Locale.US, "当前: %.7f, %.7f", pickedLa, pickedLo));
        }
    }

    private void doSearch(String keywordText) {
        if (keywordText == null || keywordText.length() == 0) return;
        if (MAP_KEY_PLACEHOLDER.equals(MAP_KEY) || MAP_KEY.isEmpty()) {
            toast("未配置腾讯地图 key，请按 README 在 local.properties 中设置 tencent.map.key");
            return;
        }
        final double centerLa = pickedLa;
        final double centerLo = pickedLo;
        final String keyword = keywordText;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = "https://apis.map.qq.com/ws/place/v1/search?keyword="
                            + URLEncoder.encode(keyword, "UTF-8")
                            + "&boundary=nearby(" + centerLa + "," + centerLo + ",10000)&page_size=8&key=" + MAP_KEY;
                    String body = httpGet(url);
                    JSONObject obj = new JSONObject(body);
                    if (obj.optInt("status", -1) != 0) {
                        throw new IllegalStateException(obj.optString("message", "unknown"));
                    }
                    JSONArray data = obj.optJSONArray("data");
                    final List<Poi> pois = new ArrayList<>();
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject item = data.optJSONObject(i);
                            if (item == null) continue;
                            JSONObject loc = item.optJSONObject("location");
                            if (loc == null) continue;
                            pois.add(new Poi(item.optString("title"), item.optString("address"),
                                    loc.optDouble("lat"), loc.optDouble("lng")));
                        }
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showResults(pois);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("搜索失败: " + t.getMessage());
                        }
                    });
                }
            }
        }, "weworkhk-search").start();
    }

    private void showResults(final List<Poi> pois) {
        if (pois.isEmpty()) {
            resultsContainer.setVisibility(View.GONE);
            toast("无结果，试着换个关键词或先移动地图");
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Poi poi : pois) {
            lines.add(poi.title + "\n" + poi.address);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(hostContext,
                android.R.layout.simple_list_item_2, android.R.id.text1, lines);
        adapter.notifyDataSetChanged();
        resultList.setAdapter(adapter);
        resultList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= pois.size()) return;
                Poi poi = pois.get(position);
                resultsContainer.setVisibility(View.GONE);
                pick(poi.lat, poi.lng);
                if (tencentMap != null) {
                    tencentMap.setCenter(new LatLng(poi.lat, poi.lng));
                    tencentMap.setZoom(17);
                }
            }
        });
        resultsContainer.setVisibility(View.VISIBLE);
    }

    private void confirm() {
        try {
            Intent intent = new Intent("weworkdk_gps");
            intent.setPackage("com.tencent.wework");
            intent.putExtra("data", pickedLa + "#" + pickedLo);
            hostContext.sendBroadcast(intent);
            toast("已更新坐标: " + String.format(java.util.Locale.US, "%.7f, %.7f", pickedLa, pickedLo));
            dismiss();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " confirm failed: " + t);
            toast("更新失败: " + t);
        }
    }

    private void dismiss() {
        activeDialog = null;
        try {
            if (mapView != null) mapView.onPause();
        } catch (Throwable ignored) {
        }
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {
        }
    }

    private void toast(String message) {
        try {
            Toast.makeText(hostContext, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code);
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) builder.append(line);
        reader.close();
        return builder.toString();
    }

    private static final class Poi {
        final String title;
        final String address;
        final double lat;
        final double lng;

        Poi(String title, String address, double lat, double lng) {
            this.title = title;
            this.address = address;
            this.lat = lat;
            this.lng = lng;
        }
    }
}
