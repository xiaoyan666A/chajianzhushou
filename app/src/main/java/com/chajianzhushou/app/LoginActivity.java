package com.chajianzhushou.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 登录界面（启动入口）：
 * - 已保存兔喜账号凭据 → 直接进入主界面（token 由 DirectApiClient 自动取缓存/自动重登）；
 * - 未保存 → 显示手机号 + 密码登录，成功后保存凭据与 token 再进入主界面。
 */
public class LoginActivity extends AppCompatActivity {

    private EditText etUsername;
    private EditText etPassword;
    private EditText etLoginCode;
    private LinearLayout pwdPanel;
    private LinearLayout codePanel;
    private TextView tvTabPwd;
    private TextView tvTabCode;
    private TextView tvTogglePwd;
    private TextView tvError;
    private Button btnLogin;
    private Button btnSendCode;
    private boolean pwdVisible = false;
    private boolean codeMode = false;

    // 获取验证码倒计时
    private static final int CODE_COUNTDOWN_SECONDS = 60;
    private final Handler countdownHandler = new Handler(Looper.getMainLooper());
    private Runnable countdownRunnable;
    private int countdownLeft = 0;

    @Override
    protected void attachBaseContext(Context newBase) {
        // 与 MainActivity 一致：进程启动时从设置读取界面字号
        if (MainActivity.sFontScale == 0f) {
            try {
                SharedPreferences prefs = newBase.getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
                MainActivity.sFontScale = prefs.getFloat("ui_font_scale", 1f);
            } catch (Exception e) {
                MainActivity.sFontScale = 1f;
            }
        }
        super.attachBaseContext(MainActivity.applyFontScale(newBase, MainActivity.sFontScale));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须与 MainActivity 同样在 super.onCreate 前应用主题
        ThemeManager.refreshSunTimesFromCachedLocation(this);
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);

        MainActivity.setAppContext(getApplicationContext());
        try { LogRecorder.getInstance(getApplicationContext()); } catch (Exception ignore) {}

        // 已有保存的凭据：直接进入主界面（后续查询时自动取缓存 token 或自动重登）
        if (new LoginStore(this).hasCredentials()) {
            goMain();
            return;
        }

        setContentView(R.layout.activity_login);
        bindViews();
        LogRecorder.info(this, "Login", "打开登录界面", "未检测到保存的登录凭据");
    }

    private void bindViews() {
        etUsername = findViewById(R.id.et_login_username);
        etPassword = findViewById(R.id.et_login_password);
        etLoginCode = findViewById(R.id.et_login_code);
        pwdPanel = findViewById(R.id.pwd_panel);
        codePanel = findViewById(R.id.code_panel);
        tvTabPwd = findViewById(R.id.tv_tab_pwd);
        tvTabCode = findViewById(R.id.tv_tab_code);
        tvTogglePwd = findViewById(R.id.tv_toggle_pwd);
        tvError = findViewById(R.id.tv_login_error);
        btnLogin = findViewById(R.id.btn_login_submit);
        btnSendCode = findViewById(R.id.btn_send_code);

        tvTogglePwd.setOnClickListener(v -> togglePasswordVisible());
        btnLogin.setOnClickListener(v -> doLogin());
        if (tvTabPwd != null) tvTabPwd.setOnClickListener(v -> setMode(false));
        if (tvTabCode != null) tvTabCode.setOnClickListener(v -> setMode(true));
        if (btnSendCode != null) btnSendCode.setOnClickListener(v -> sendCode());
        setMode(false);
    }

    /** 切换登录方式：false=密码登录，true=验证码登录 */
    private void setMode(boolean code) {
        codeMode = code;
        if (pwdPanel != null) pwdPanel.setVisibility(code ? View.GONE : View.VISIBLE);
        if (codePanel != null) codePanel.setVisibility(code ? View.VISIBLE : View.GONE);
        if (tvTabPwd != null) {
            tvTabPwd.setTextColor(getResources().getColor(code ? R.color.muted : R.color.accent, getTheme()));
        }
        if (tvTabCode != null) {
            tvTabCode.setTextColor(getResources().getColor(code ? R.color.accent : R.color.muted, getTheme()));
        }
        if (tvError != null) tvError.setVisibility(View.GONE);
    }

    /** 发送短信验证码：校验手机号 → 调网关 → 成功后 60 秒倒计时 */
    private void sendCode() {
        if (btnSendCode == null || etUsername == null) return;
        final String phone = etUsername.getText().toString().trim();
        if (!phone.matches("^1\\d{10}$")) {
            showError("请输入正确的 11 位手机号");
            return;
        }
        btnSendCode.setEnabled(false);
        btnSendCode.setText("发送中...");
        Threads.io().execute(() -> {
            try {
                final org.json.JSONObject result = DirectApiClient.sendLoginCode(LoginActivity.this, phone);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    int isRisk = result == null ? 0 : result.optInt("isRisk", 0);
                    if (isRisk == 1) {
                        showError("发送验证码需要完成滑块验证，请先在兔喜官方App获取验证码");
                    } else {
                        if (tvError != null) tvError.setVisibility(View.GONE);
                        Toast.makeText(this, "验证码已发送，请注意查收短信", Toast.LENGTH_SHORT).show();
                    }
                    startCountdown();
                });
            } catch (Exception e) {
                final String msg = e == null ? "发送失败" : e.getMessage();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (btnSendCode != null) {
                        btnSendCode.setEnabled(true);
                        btnSendCode.setText("获取验证码");
                    }
                    showError(msg);
                });
            }
        });
    }

    private void startCountdown() {
        stopCountdown();
        countdownLeft = CODE_COUNTDOWN_SECONDS;
        countdownRunnable = new Runnable() {
            @Override public void run() {
                if (countdownLeft <= 0) {
                    stopCountdown();
                    if (btnSendCode != null) {
                        btnSendCode.setEnabled(true);
                        btnSendCode.setText("获取验证码");
                    }
                    return;
                }
                if (btnSendCode != null) {
                    btnSendCode.setEnabled(false);
                    btnSendCode.setText(countdownLeft + "s 后重发");
                }
                countdownLeft--;
                countdownHandler.postDelayed(this, 1000L);
            }
        };
        countdownHandler.post(countdownRunnable);
    }

    private void stopCountdown() {
        if (countdownRunnable != null) {
            countdownHandler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
    }

    private void togglePasswordVisible() {
        pwdVisible = !pwdVisible;
        int type = pwdVisible
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setInputType(type);
        // 光标移回末尾，避免切换类型时跳动
        etPassword.setSelection(etPassword.length());
        tvTogglePwd.setText(pwdVisible ? "隐藏" : "显示");
    }

    private void showError(String msg) {
        if (tvError != null) {
            tvError.setText(msg == null ? "登录失败" : msg);
            tvError.setVisibility(View.VISIBLE);
        }
        if (btnLogin != null) {
            btnLogin.setEnabled(true);
            btnLogin.setText("登 录");
        }
    }

    private void doLogin() {
        if (btnLogin == null || etUsername == null) return;
        final String username = etUsername.getText().toString().trim();
        if (username.length() == 0) {
            showError("请输入手机号");
            return;
        }
        final String password = etPassword == null ? "" : etPassword.getText().toString();
        final String code = etLoginCode == null ? "" : etLoginCode.getText().toString().trim();
        if (codeMode) {
            if (code.length() == 0) {
                showError("请输入验证码");
                return;
            }
        } else {
            if (password.length() == 0) {
                showError("请输入密码");
                return;
            }
        }

        btnLogin.setEnabled(false);
        btnLogin.setText("登录中...");
        if (tvError != null) tvError.setVisibility(View.GONE);

        Threads.io().execute(() -> {
            try {
                // 成功后内部已保存凭据与 token 缓存
                if (codeMode) {
                    DirectApiClient.loginByCode(LoginActivity.this, username, code);
                } else {
                    DirectApiClient.login(LoginActivity.this, username, password);
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    LogRecorder.info(this, "Login", "登录成功", "方式=" + (codeMode ? "验证码" : "密码"));
                    goMain();
                });
            } catch (Exception e) {
                final String msg = e == null ? "登录失败" : e.getMessage();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    try { LogRecorder.error(LoginActivity.this, "Login", "登录失败", msg); } catch (Exception ignore) {}
                    showError(msg);
                });
            }
        });
    }

    private void goMain() {
        stopCountdown();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    @Override
    protected void onDestroy() {
        stopCountdown();
        super.onDestroy();
    }
}
