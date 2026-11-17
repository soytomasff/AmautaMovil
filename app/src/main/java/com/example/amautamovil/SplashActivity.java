package com.example.amautamovil;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.VideoView;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Plays the intro once per app launch while validating the in-memory session. */
public class SplashActivity extends Activity {
    private final AmautaApi api = AmautaApi.get();
    private final ExecutorService verifier = Executors.newSingleThreadExecutor();
    private VideoView video;
    private boolean videoFinished;
    private boolean sessionResolved;
    private boolean resumed;
    private boolean navigated;
    private String verifiedRole;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemBars();
        setContentView(R.layout.activity_splash);
        video = findViewById(R.id.introVideo);
        video.setOnPreparedListener(this::prepared);
        video.setOnCompletionListener(player -> finishVideo());
        video.setOnErrorListener((player, what, extra) -> {
            finishVideo();
            return true; // Suppress the default playback error dialog.
        });

        verifySession();
        try {
            video.setVideoURI(Uri.parse("android.resource://" + getPackageName()
                    + "/" + R.raw.amauta_intro));
            video.start();
        } catch (RuntimeException error) {
            finishVideo();
        }
    }

    private void prepared(MediaPlayer player) {
        player.setLooping(false);
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio != null && audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL)
            player.setVolume(0f, 0f);
        findViewById(R.id.introCover).setVisibility(View.GONE);
    }

    private void verifySession() {
        if (!api.hasSession()) {
            sessionResolved = true;
            advanceIfReady();
            return;
        }
        verifier.execute(() -> {
            String role = null;
            try {
                JSONObject account = api.me();
                role = account.optString("rol", "");
            } catch (Exception error) {
                if (error instanceof AmautaApi.ApiException
                        && ((AmautaApi.ApiException) error).status == 401) api.clear();
            }
            String result = role;
            runOnUiThread(() -> {
                if (isDestroyed() || navigated) return;
                verifiedRole = result;
                sessionResolved = true;
                advanceIfReady();
            });
        });
    }

    private void finishVideo() {
        videoFinished = true;
        advanceIfReady();
    }

    private void advanceIfReady() {
        if (navigated || !resumed || !videoFinished || !sessionResolved
                || isFinishing() || isDestroyed()) return;
        navigated = true;
        video.stopPlayback();
        Intent next = new Intent(this, MainActivity.class);
        if (verifiedRole != null && !verifiedRole.isEmpty() && api.hasSession())
            next.putExtra(MainActivity.EXTRA_VERIFIED_ROLE, verifiedRole);
        startActivity(next);
        finish();
        overridePendingTransition(0, 0);
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        bars.hide(WindowInsetsCompat.Type.systemBars());
        bars.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (video != null && !videoFinished) video.start();
        advanceIfReady();
    }

    @Override protected void onPause() {
        resumed = false;
        if (video != null && !videoFinished) video.pause();
        super.onPause();
    }

    @Override public void onBackPressed() { finishVideo(); }

    @Override protected void onDestroy() {
        if (video != null) video.stopPlayback();
        verifier.shutdownNow();
        super.onDestroy();
    }
}
