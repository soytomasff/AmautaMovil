package com.example.amautamovil;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Uses the same checkout contract as the web app; there is no start-cleaning endpoint. */
public class CleaningActivity extends AppCompatActivity {
    private final AmautaApi api = AmautaApi.get();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private JSONArray rooms;
    private boolean busy;
    private volatile boolean changed;
    private boolean cleanerOnly;
    private boolean canFinalize;
    private int generation;
    private int filter;
    private String query = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        cleanerOnly = getIntent().getBooleanExtra("cleanerOnly", false);
        setContentView(R.layout.activity_cleaning);
        applyInsets();
        if (cleanerOnly) {
            findViewById(R.id.navHome).setVisibility(View.GONE);
            findViewById(R.id.navRooms).setVisibility(View.GONE);
            findViewById(R.id.navGuests).setVisibility(View.GONE);
            findViewById(R.id.navReservations).setVisibility(View.GONE);
            findViewById(R.id.cleaningLogout).setVisibility(View.VISIBLE);
            findViewById(R.id.cleaningLogout).setOnClickListener(v -> logout());
        }
        findViewById(R.id.navReservations).setSelected(true);
        findViewById(R.id.navHome).setOnClickListener(v -> returnTo("home"));
        findViewById(R.id.navRooms).setOnClickListener(v -> returnTo("rooms"));
        findViewById(R.id.navGuests).setOnClickListener(v -> returnTo("guests"));
        findViewById(R.id.navReservations).setOnClickListener(v -> {
            if (busy) return;
            new AlertDialog.Builder(this).setItems(new String[] {
                    getString(R.string.reservations_title), getString(R.string.reception_title),
                    getString(R.string.cleaning_title)
            }, (dialog, which) -> {
                if (which == 0) returnTo("reservations");
                else if (which == 1) returnTo("reception");
            }).show();
        });
        Spinner selector = findViewById(R.id.cleaningFilter);
        String[] labels = {getString(R.string.cleaning_all), getString(R.string.cleaning_pending),
                getString(R.string.cleaning_clean), getString(R.string.cleaning_maintenance),
                getString(R.string.cleaning_occupied)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        selector.setAdapter(adapter);
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                filter = position;
                render();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        ((EditText) findViewById(R.id.cleaningSearch)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        findViewById(R.id.cleaningRefresh).setOnClickListener(v -> loadRooms());
        loadRooms();
    }

    private void applyInsets() {
        View root = ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        int left = root.getPaddingLeft(), top = root.getPaddingTop();
        int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        if (getResources().getConfiguration().smallestScreenWidthDp >= 600) {
            View column = findViewById(R.id.contentColumn);
            android.view.ViewGroup.LayoutParams params = column.getLayoutParams();
            params.width = (int) (Math.min(760, getResources().getConfiguration().screenWidthDp - 32)
                    * getResources().getDisplayMetrics().density);
            if (params instanceof LinearLayout.LayoutParams)
                ((LinearLayout.LayoutParams) params).gravity = android.view.Gravity.CENTER_HORIZONTAL;
            column.setLayoutParams(params);
        }
    }

    private void loadRooms() {
        if (busy) return;
        busy = true;
        setLoading(true);
        status(getString(R.string.loading_rooms), false);
        int current = generation;
        worker.execute(() -> {
            try {
                JSONObject account = api.me();
                String role = account.optString("rol", "").trim().toUpperCase(Locale.ROOT);
                if (!java.util.Arrays.asList("ADMIN", "ADMINISTRADOR", "JEFE", "RECEPCION",
                        "RECEPCIONISTA", "RECEPCION_CAJA", "LIMPIEZA", "PERSONAL_LIMPIEZA").contains(role))
                    throw new AmautaApi.ApiException(403, "");
                boolean allowedToFinish = java.util.Arrays.asList("ADMIN", "ADMINISTRADOR", "JEFE",
                        "LIMPIEZA", "PERSONAL_LIMPIEZA").contains(role);
                JSONArray latest = api.rooms();
                runOnUiThread(() -> {
                    if (current != generation) return;
                    rooms = latest;
                    canFinalize = allowedToFinish;
                    busy = false;
                    setLoading(false);
                    render();
                });
            } catch (Exception error) { fail(current, error); }
        });
    }

    private void render() {
        if (rooms == null) return;
        LinearLayout list = findViewById(R.id.cleaningList);
        list.removeAllViews();
        int pending = 0, clean = 0, maintenance = 0, shown = 0;
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject room = rooms.optJSONObject(i);
            if (room != null) sorted.add(room);
        }
        sorted.sort((a, b) -> RoomRules.compareNumbers(a.optString("numero"), b.optString("numero")));
        for (JSONObject room : sorted) {
            int category = category(room);
            if (!room.isNull("limpiezaPendienteDesde")) pending++;
            if (category == 2) clean++;
            if (category == 3) maintenance++;
            String number = room.optString("numero", room.optString("codigo", "—"));
            if (filter != 0 && category != filter) continue;
            if (!number.toLowerCase(Locale.ROOT).contains(query)) continue;
            shown++;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackgroundResource(R.drawable.bg_card);
            card.setElevation(dp(2));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.bottomMargin = dp(12);
            list.addView(card, cardParams);
            TextView title = new TextView(this);
            title.setText(getString(R.string.room_number, number));
            title.setTextSize(19);
            title.setTextColor(ContextCompat.getColor(this, R.color.ink));
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(title);
            TextView state = new TextView(this);
            state.setText(state(room));
            state.setTextColor(ContextCompat.getColor(this, category == 1 ? R.color.cleaning_text
                    : category == 2 ? R.color.free_text : category == 3 ? R.color.maintenance_text
                    : R.color.occupied_text));
            state.setPadding(0, dp(6), 0, dp(6));
            card.addView(state);
            long id = room.optLong("id", -1);
            Button details = button(getString(R.string.cleaning_detail), false);
            details.setEnabled(!busy && id > 0);
            details.setOnClickListener(v -> showDetail(id));
            card.addView(details);
            if (canFinalize && canFinish(room)) {
                Button finish = button(getString(R.string.cleaning_finish), true);
                finish.setEnabled(!busy && id > 0);
                finish.setOnClickListener(v -> prepareFinish(id));
                card.addView(finish);
            }
        }
        ((TextView) findViewById(R.id.cleaningSummary)).setText(
                getString(R.string.cleaning_summary, pending, clean, maintenance));
        if (!busy) status(shown == 0 ? getString(R.string.cleaning_empty) : "", false);
    }

    private int category(JSONObject room) {
        String state = room.optString("estado", "").trim().toUpperCase(Locale.ROOT);
        String operational = state(room).trim().toUpperCase(Locale.ROOT);
        if (state.equals("MANTENIMIENTO")) return 3;
        if (!room.isNull("limpiezaPendienteDesde") || state.equals("PENDIENTE DE LIMPIEZA")) return 1;
        if (operational.equals("SALIDA PENDIENTE")) return 4;
        if (state.equals("LIBRE") || state.equals("DISPONIBLE")) return 2;
        return 4;
    }

    private boolean canFinish(JSONObject room) {
        return room.optString("estado").equalsIgnoreCase("PENDIENTE DE LIMPIEZA")
                && !room.isNull("limpiezaPendienteDesde")
                && !state(room).equalsIgnoreCase("SALIDA PENDIENTE");
    }

    private String state(JSONObject room) {
        String operational = room.optString("estadoOperativo", "");
        return operational.isEmpty() || operational.equals("null")
                ? room.optString("estado", getString(R.string.not_available)) : operational;
    }

    private void showDetail(long id) {
        if (busy) return;
        busy = true;
        setLoading(true);
        status(getString(R.string.loading_room_detail), false);
        int current = generation;
        worker.execute(() -> {
            try {
                JSONArray latest = api.rooms();
                JSONObject room = find(latest, id);
                if (room == null) throw new AmautaApi.ApiException(404, "");
                JSONArray movements = api.roomMovements(id);
                runOnUiThread(() -> {
                    if (current != generation) return;
                    rooms = latest;
                    busy = false;
                    setLoading(false);
                    render();
                    String since = room.isNull("limpiezaPendienteDesde") ? ""
                            : getString(R.string.cleaning_since,
                                    room.optString("limpiezaPendienteDesde").replace('T', ' '));
                    StringBuilder description = new StringBuilder(getString(R.string.cleaning_detail_text,
                            room.optString("numero", "—"), room.optString("tipo", "—"),
                            room.optString("capacidad", "—"), state(room), since));
                    description.append("\n\n").append(getString(R.string.cleaning_history));
                    int count = 0;
                    for (int i = movements.length() - 1; i >= 0; i--) {
                        JSONObject move = movements.optJSONObject(i);
                        if (move == null || !move.optString("tipo").startsWith("LIMPIEZA")) continue;
                        count++;
                        description.append("\n\n").append(getString(R.string.cleaning_history_row,
                                move.optString("momento", "—").replace('T', ' '),
                                move.optString("usuario", "—"), move.optString("tipo", "—"),
                                move.optString("detalle", "")));
                    }
                    if (count == 0) description.append("\n").append(getString(R.string.cleaning_no_history));
                    AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                            .setTitle(R.string.cleaning_title).setMessage(description.toString())
                            .setNegativeButton(android.R.string.ok, null);
                    if (canFinalize && canFinish(room)) dialog.setPositiveButton(R.string.cleaning_finish,
                            (d, which) -> prepareFinish(id));
                    dialog.show();
                });
            } catch (Exception error) { fail(current, error); }
        });
    }

    private void prepareFinish(long id) {
        if (busy || !canFinalize) return;
        busy = true;
        setLoading(true);
        status(getString(R.string.loading_room_detail), false);
        int current = generation;
        worker.execute(() -> {
            try {
                JSONArray latest = api.rooms();
                JSONObject room = find(latest, id);
                if (room == null) throw new AmautaApi.ApiException(404, "");
                if (!canFinish(room)) throw new AmautaApi.ApiException(409, "");
                // Cleaning staff cannot list stays. The checkout service locks the room
                // and checks active guests again when the confirmation is submitted.
                runOnUiThread(() -> {
                    if (current != generation) return;
                    rooms = latest;
                    setLoading(false);
                    render();
                    new AlertDialog.Builder(this)
                            .setMessage(getString(R.string.cleaning_confirm, room.optString("numero", "—")))
                            .setNegativeButton(R.string.cancel, (d, which) -> { busy = false; render(); })
                            .setOnCancelListener(d -> { busy = false; render(); })
                            .setPositiveButton(R.string.confirm, (d, which) -> finishCleaning(id))
                            .show();
                });
            } catch (Exception error) { fail(current, error); }
        });
    }

    private void finishCleaning(long id) {
        status(getString(R.string.operation_running), false);
        setLoading(true);
        int current = generation;
        worker.execute(() -> {
            try {
                api.completeCleaning(id);
                changed = true;
                try {
                    JSONArray latest = api.rooms();
                    runOnUiThread(() -> {
                        if (current != generation) return;
                        rooms = latest;
                        busy = false;
                        setLoading(false);
                        render();
                        status(getString(R.string.cleaning_done), false);
                    });
                } catch (Exception refreshError) {
                    runOnUiThread(() -> {
                        if (current != generation) return;
                        busy = false;
                        setLoading(false);
                        render();
                        if (refreshError instanceof AmautaApi.ApiException
                                && ((AmautaApi.ApiException) refreshError).status == 401) {
                            api.clear();
                            if (cleanerOnly) openLogin(); else returnTo("login");
                            return;
                        }
                        status(getString(R.string.cleaning_done_refresh_failed), true);
                    });
                }
            } catch (Exception error) { fail(current, error); }
        });
    }

    private JSONObject find(JSONArray list, long id) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject room = list.optJSONObject(i);
            if (room != null && room.optLong("id", -1) == id) return room;
        }
        return null;
    }

    private Button button(String label, boolean primary) {
        Button result = new Button(this);
        result.setText(label);
        result.setMinHeight(dp(52));
        if (primary) result.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.gold)));
        return result;
    }

    private void setLoading(boolean loading) {
        ((ProgressBar) findViewById(R.id.cleaningProgress)).setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.cleaningRefresh).setEnabled(!loading);
    }

    private void status(String message, boolean error) {
        TextView view = findViewById(R.id.cleaningMessage);
        view.setText(message);
        view.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        view.setTextColor(ContextCompat.getColor(this, error ? R.color.error : R.color.muted));
    }

    private void fail(int current, Exception error) {
        runOnUiThread(() -> {
            if (current != generation) return;
            busy = false;
            setLoading(false);
            if (error instanceof AmautaApi.ApiException) {
                int code = ((AmautaApi.ApiException) error).status;
                if (code == 401) {
                    api.clear();
                    if (cleanerOnly) openLogin(); else returnTo("login");
                    return;
                }
                if (code == 403) { render(); status(getString(R.string.operation_denied), true); return; }
                if (code == 404) { render(); status(getString(R.string.room_not_found), true); return; }
                if (code == 409) { render(); status(getString(R.string.operation_conflict), true); return; }
            }
            render();
            status(getString(error instanceof java.io.IOException
                    ? R.string.connection_error : R.string.server_error), true);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void returnTo(String destination) {
        if (busy && !"login".equals(destination)) return;
        setResult(RESULT_OK, new Intent().putExtra("destination", destination)
                .putExtra("refreshRooms", changed));
        finish();
    }

    private void logout() {
        if (busy) return;
        busy = true;
        setLoading(true);
        worker.execute(() -> {
            try { api.logout(); }
            catch (Exception ignored) { api.clear(); }
            runOnUiThread(this::openLogin);
        });
    }

    private void openLogin() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override public void onBackPressed() {
        if (cleanerOnly) finish(); else returnTo("home");
    }

    @Override protected void onDestroy() {
        generation++;
        worker.shutdown();
        super.onDestroy();
    }
}
