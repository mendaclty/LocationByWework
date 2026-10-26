package org.gallonyin.weworkhk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.zzti.fengyongge.imagepicker.PhotoSelectorActivity;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 模块主界面（可选）：手动输入坐标、腾讯地图拾取坐标、照片注入、总开关。
 *
 * <p>日常使用不需要打开这个界面：在Wework内按音量键即可呼出选点弹窗。
 * 这里的坐标会通过 {@code weworkdk_gps} 广播同步给Wework各进程中的 hook。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    /** 照片目录（照片注入功能使用）。 */
    private static final String ATTENDANCE_DIR = "/storage/emulated/0/Tencent/WeixinWork/data/attendance/";

    private EditText etLa;
    private EditText etLo;
    private EditText etPicPath;
    private ImageView ivPickPic;
    private CheckBox cbOpen;
    private SharedPreferences sp;
    private boolean injectPicSuccess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        sp = getSharedPreferences("main", Context.MODE_PRIVATE);
        initView();
    }

    private void initView() {
        etLa = findViewById(R.id.et_la);
        etLo = findViewById(R.id.et_lo);
        etPicPath = findViewById(R.id.et_pic_path);
        ivPickPic = findViewById(R.id.iv_pick_pic);
        cbOpen = findViewById(R.id.cb_open);

        etLa.setText(trimNumber(sp.getFloat("GPSLatitude", 32.041713f)));
        etLo.setText(trimNumber(sp.getFloat("GPSLongitude", 118.784308f)));
        etPicPath.setText(sp.getString("PicPath", ""));

        cbOpen.setChecked(sp.getBoolean("open", true));
        cbOpen.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean("open", isChecked).apply();
                Intent intent = new Intent("weworkdk_open");
                intent.putExtra("open", isChecked);
                sendBroadcast(intent);
            }
        });

        findViewById(R.id.bt_gps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TencentMapActivity.enterActivity(MainActivity.this);
            }
        });

        findViewById(R.id.bt_save_location).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCoordinates();
            }
        });

        findViewById(R.id.bt_pick_pic).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, PhotoSelectorActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                intent.putExtra("limit", 1); // 选择图片的数量
                startActivityForResult(intent, 0);
            }
        });

        findViewById(R.id.bt_save_pic).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                injectPicSuccess = false;
                final String picPath = etPicPath.getText().toString().trim();
                Intent intent = new Intent("weworkdk_pic");
                intent.putExtra("data", picPath);
                sendBroadcast(intent);
                ivPickPic.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this,
                                injectPicSuccess ? "图片注入成功" : "疑似注入失败，请确认Wework已启动",
                                Toast.LENGTH_SHORT).show();
                    }
                }, 500);
                sp.edit().putString("PicPath", picPath).apply();
            }
        });

        TextView tvConfirm = findViewById(R.id.tv_confirm);
        tvConfirm.setText(confirm() ? "插件已正常启动" : "插件启动失败：请在 LSPosed 中启用模块并勾选Wework");

        IntentFilter filter = new IntentFilter("weworkdk_pic_success");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "onReceive: " + intent.getAction());
                injectPicSuccess = true;
            }
        }, filter);
    }

    private void saveCoordinates() {
        String la = etLa.getText().toString().trim();
        String lo = etLo.getText().toString().trim();
        double latitude;
        double longitude;
        try {
            latitude = Double.parseDouble(la);
            longitude = Double.parseDouble(lo);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "坐标格式异常", Toast.LENGTH_LONG).show();
            return;
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            Toast.makeText(this, "经纬度超出范围", Toast.LENGTH_LONG).show();
            return;
        }
        sp.edit()
                .putFloat("GPSLatitude", (float) latitude)
                .putFloat("GPSLongitude", (float) longitude)
                .putFloat("la", (float) latitude)
                .putFloat("lo", (float) longitude)
                .apply();
        Intent intent = new Intent("weworkdk_gps");
        intent.setPackage("com.tencent.wework");
        // 广播传递全精度字符串，避免 float 精度损失
        intent.putExtra("data", latitude + "#" + longitude);
        sendBroadcast(intent);
        Toast.makeText(this, "保存修改成功", Toast.LENGTH_SHORT).show();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 0 && resultCode == RESULT_OK && data != null && data.getExtras() != null) {
            List<String> paths = (List<String>) data.getExtras().getSerializable("photos");
            if (paths != null && paths.size() == 1) {
                File imgFile = new File(paths.get(0));
                if (!imgFile.exists()) {
                    Toast.makeText(this, "图片不存在", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    File newFile = new File(ATTENDANCE_DIR + imgFile.getName());
                    Util.copy(imgFile, newFile);
                    Bitmap bitmap = BitmapFactory.decodeFile(newFile.getAbsolutePath());
                    etPicPath.setText(newFile.getAbsolutePath());
                    ivPickPic.setImageBitmap(bitmap);
                } catch (IOException e) {
                    Log.e(TAG, "copy photo failed", e);
                    Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private static String trimNumber(float value) {
        return String.format(java.util.Locale.US, "%.7f", value);
    }

    /**
     * 由 {@link Main} 在模块生效时 hook 为 true，用于在界面上提示插件是否已正常加载。
     * 未生效时保持 false。
     */
    private boolean confirm() {
        return false;
    }
}
