package com.example.amautamovil;

import android.os.Bundle;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    static final String EXTRA_VERIFIED_ROLE = "com.example.amautamovil.VERIFIED_ROLE";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AmautaApi api = AmautaApi.get();
    private int generation;
    private boolean busy;
    private JSONArray lastRooms;
    private long selectedRoomId = -1;
    private int roomFilter = RoomRules.ALL;
    private String currentRole = "";
    private boolean operationBusy;
    private boolean roomRefreshRequired;
    private JSONArray lastGuests;
    private String guestQuery = "";
    private boolean savingGuest;
    private int guestNotice;
    private int screen;
    private long editingGuestId = -1;
    private JSONArray lastReservations;
    private JSONArray reservationGuests;
    private JSONArray reservationRooms;
    private JSONArray reservationChoices;
    private final List<Long> reservationGuestIds = new ArrayList<>();
    private final List<Long> reservationRoomIds = new ArrayList<>();
    private String reservationQuery = "";
    private String reservationStatusFilter = "";
    private String reservationEntry = "";
    private String reservationExit = "";
    private String reservationState = "RESERVADO";
    private long reservationGuestId = -1;
    private long reservationRoomId = -1;
    private JSONObject editingReservation;
    private JSONObject currentReservation;
    private int reservationNotice;
    private static final int LOGIN = 0, HOME = 1, ROOMS = 2, ROOM_DETAIL = 3,
            GUESTS = 4, GUEST_FORM = 5, RESERVATIONS = 6, GUEST_DETAIL = 7,
            RESERVATION_FORM = 8, RESERVATION_DETAIL = 9;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        String verifiedRole = getIntent().getStringExtra(EXTRA_VERIFIED_ROLE);
        if (verifiedRole != null && !verifiedRole.isEmpty() && api.hasSession()) {
            currentRole = verifiedRole;
            openForRole();
            return;
        }
        showLogin(null);
        if (api.hasSession()) restore();
    }

    private void restore() {
        TextView message = findViewById(R.id.loginMessage);
        showStatus(message, R.string.restoring_session, false);
        Button login = findViewById(R.id.loginButton);
        login.setEnabled(false);
        int current = ++generation;
        worker.execute(() -> {
            try {
                JSONObject user = api.me();
                runOnUiThread(() -> {
                    if (current == generation) {
                        currentRole = user.optString("rol", "");
                        openForRole();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    if (error instanceof AmautaApi.ApiException
                            && ((AmautaApi.ApiException) error).status == 401) api.clear();
                    login.setEnabled(true);
                    showStatus(message, error instanceof AmautaApi.ApiException
                            && ((AmautaApi.ApiException) error).status == 401
                            ? R.string.session_expired : R.string.connection_error, true);
                });
            }
        });
    }

    private void showLogin(String error) {
        generation++;
        busy = false;
        lastRooms = null;
        lastGuests = null;
        guestQuery = "";
        selectedRoomId = -1;
        currentRole = "";
        operationBusy = false;
        roomRefreshRequired = false;
        savingGuest = false;
        guestNotice = 0;
        lastReservations = null;
        reservationNotice = 0;
        screen = LOGIN;
        setContentView(R.layout.activity_main);
        applyInsets();
        TextView message = findViewById(R.id.loginMessage);
        if (error != null) showStatus(message, error, true);
        EditText username = findViewById(R.id.username);
        EditText password = findViewById(R.id.password);
        Button login = findViewById(R.id.loginButton);
        password.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                login.performClick();
                return true;
            }
            return false;
        });
        login.setOnClickListener(view -> {
            String user = username.getText().toString().trim();
            String secret = password.getText().toString();
            if (user.isEmpty() || secret.isEmpty()) {
                if (user.isEmpty()) username.setError(getString(R.string.username_required));
                if (secret.isEmpty()) password.setError(getString(R.string.password_required));
                if (user.isEmpty()) username.requestFocus();
                else password.requestFocus();
                return;
            }
            if (busy) return;
            busy = true;
            login.setEnabled(false);
            showStatus(message, getString(R.string.signing_in), false);
            int current = generation;
            worker.execute(() -> {
                try {
                    JSONObject account = api.login(user, secret);
                    runOnUiThread(() -> {
                        if (current == generation) {
                            currentRole = account.optString("rol", "");
                            openForRole();
                        }
                    });
                } catch (Exception failure) {
                    runOnUiThread(() -> {
                        if (current != generation) return;
                        busy = false;
                        login.setEnabled(true);
                        if (failure instanceof AmautaApi.ApiException
                                && ((AmautaApi.ApiException) failure).status == 401) {
                            password.setText("");
                            password.setError(getString(R.string.invalid_credentials));
                            password.requestFocus();
                        }
                        showStatus(message, getString(failure instanceof AmautaApi.ApiException
                                && ((AmautaApi.ApiException) failure).status == 401
                                ? R.string.invalid_credentials : errorMessage(failure)), true);
                    });
                }
            });
        });
    }

    private void setupNavigation() {
        findViewById(R.id.navHome).setSelected(screen == HOME);
        findViewById(R.id.navRooms).setSelected(screen == ROOMS || screen == ROOM_DETAIL);
        findViewById(R.id.navGuests).setSelected(screen == GUESTS || screen == GUEST_FORM
                || screen == GUEST_DETAIL);
        findViewById(R.id.navReservations).setSelected(screen == RESERVATIONS
                || screen == RESERVATION_FORM || screen == RESERVATION_DETAIL);
        findViewById(R.id.navHome).setOnClickListener(view -> {
            if (!operationBusy && !savingGuest && screen != HOME) showHome();
        });
        findViewById(R.id.navRooms).setOnClickListener(view -> {
            if (!operationBusy && !savingGuest && screen != ROOMS) showRooms();
        });
        findViewById(R.id.navGuests).setOnClickListener(view -> {
            if (!operationBusy && !savingGuest && screen != GUESTS) showGuests(true);
        });
        findViewById(R.id.navReservations).setOnClickListener(view -> {
            if (operationBusy || savingGuest) return;
            new AlertDialog.Builder(this).setItems(new String[] {
                    getString(R.string.reservations_title), getString(R.string.reception_title),
                    getString(R.string.cleaning_title)
            }, (dialog, choice) -> {
                if (choice == 0) showReservations();
                else if (!canReception()) new AlertDialog.Builder(this).setMessage(R.string.operation_denied)
                        .setPositiveButton(android.R.string.ok, null).show();
                else if (choice == 1) startActivityForResult(
                        new Intent(this, ReceptionActivity.class), 620);
                else startActivityForResult(new Intent(this, CleaningActivity.class), 620);
            }).show();
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 620 || resultCode != RESULT_OK || data == null) return;
        String destination = data.getStringExtra("destination");
        if (data.getBooleanExtra("refreshRooms", false)) {
            roomRefreshRequired = true;
            lastRooms = null;
            lastReservations = null;
        }
        if ("login".equals(destination)) { showLogin(getString(R.string.session_expired)); return; }
        if ("home".equals(destination)) showHome();
        else if ("rooms".equals(destination)) showRooms();
        else if ("guests".equals(destination)) showGuests(true);
        else if ("reservations".equals(destination)) showReservations();
        else if ("cleaning".equals(destination) && canReception())
            startActivityForResult(new Intent(this, CleaningActivity.class), 620);
        else if ("reception".equals(destination) && canReception())
            startActivityForResult(new Intent(this, ReceptionActivity.class), 620);
        else if (data.getBooleanExtra("refreshRooms", false)) {
            if (screen == HOME) showHome();
            else if (screen == ROOMS || screen == ROOM_DETAIL) showRooms();
            else if (screen == RESERVATIONS || screen == RESERVATION_DETAIL) showReservations();
        }
    }

    private void applyInsets() {
        View root = ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        if (getResources().getConfiguration().smallestScreenWidthDp >= 600) {
            limitContentWidth(findViewById(R.id.contentColumn));
        }
        int left = root.getPaddingLeft(), top = root.getPaddingTop();
        int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(left + bars.left, top + bars.top,
                    right + bars.right, bottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void limitContentWidth(View content) {
        if (content == null) return;
        android.view.ViewGroup.LayoutParams params = content.getLayoutParams();
        params.width = dp(Math.min(760, getResources().getConfiguration().screenWidthDp - 32));
        if (params instanceof FrameLayout.LayoutParams)
            ((FrameLayout.LayoutParams) params).gravity = android.view.Gravity.CENTER_HORIZONTAL;
        else if (params instanceof LinearLayout.LayoutParams)
            ((LinearLayout.LayoutParams) params).gravity = android.view.Gravity.CENTER_HORIZONTAL;
        content.setLayoutParams(params);
    }

    private void showStatus(TextView view, CharSequence value, boolean error) {
        if (value == null || value.length() == 0) {
            view.setText("");
            view.setVisibility(View.GONE);
            return;
        }
        view.setText(value);
        view.setVisibility(View.VISIBLE);
        view.setTextColor(ContextCompat.getColor(this, error ? R.color.error : R.color.muted));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(this,
                error ? R.color.error_surface : R.color.info_surface));
        background.setCornerRadius(dp(12));
        view.setBackground(background);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding, padding, padding);
    }

    private void showStatus(TextView view, int stringId, boolean error) {
        showStatus(view, getString(stringId), error);
    }

    private void showHome() {
        if (operationBusy || savingGuest) return;
        generation++;
        screen = HOME;
        busy = false;
        selectedRoomId = -1;
        setContentView(R.layout.activity_home);
        applyInsets();
        setupNavigation();
        buildMetrics();
        findViewById(R.id.homeRefresh).setOnClickListener(view -> loadHome());
        findViewById(R.id.homeLogout).setOnClickListener(view -> logout());
        findViewById(R.id.homeRooms).setOnClickListener(view -> showRooms());
        findViewById(R.id.homeGuests).setOnClickListener(view -> showGuests(true));
        findViewById(R.id.homeOpenReservations).setOnClickListener(view -> showReservations());
        loadHome();
    }

    private void buildMetrics() {
        GridLayout grid = findViewById(R.id.metricGrid);
        addMetric(grid, R.id.homeAvailable, R.string.home_available,
                R.drawable.ic_nav_rooms, R.color.free_text);
        addMetric(grid, R.id.homeOccupied, R.string.home_occupied,
                R.drawable.ic_nav_rooms, R.color.occupied_text);
        addMetric(grid, R.id.homeCleaning, R.string.home_cleaning,
                R.drawable.ic_nav_rooms, R.color.cleaning_text);
        addMetric(grid, R.id.homeMaintenance, R.string.home_maintenance,
                R.drawable.ic_nav_rooms, R.color.maintenance_text);
        addMetric(grid, R.id.homeRegistered, R.string.registered_guests,
                R.drawable.ic_nav_guests, R.color.brand);
        addMetric(grid, R.id.homeCurrent, R.string.current_guests,
                R.drawable.ic_nav_guests, R.color.brand);
        addMetric(grid, R.id.homeReservations, R.string.reservations_title,
                R.drawable.ic_nav_reservations, R.color.gold);
    }

    private void addMetric(GridLayout grid, int valueId, int labelId, int iconId, int accentId) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setMinimumHeight(dp(138));
        card.setPadding(dp(16), dp(15), dp(14), dp(12));
        card.setBackgroundResource(R.drawable.bg_card);
        card.setElevation(dp(3));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = -2;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(5), dp(5), dp(5), dp(5));
        grid.addView(card, params);
        View stripe = new View(this);
        stripe.setBackgroundColor(ContextCompat.getColor(this, accentId));
        card.addView(stripe, new LinearLayout.LayoutParams(dp(36), dp(4)));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconId);
        icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, accentId)));
        icon.setContentDescription(null);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconParams.topMargin = dp(10);
        card.addView(icon, iconParams);
        TextView label = new TextView(this);
        label.setText(labelId);
        label.setTextSize(13);
        label.setTextColor(ContextCompat.getColor(this, R.color.muted));
        label.setMaxLines(2);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
        labelParams.topMargin = dp(6);
        card.addView(label, labelParams);
        TextView value = new TextView(this);
        value.setId(valueId);
        value.setText("—");
        value.setTextSize(29);
        value.setTypeface(null, android.graphics.Typeface.BOLD);
        value.setTextColor(ContextCompat.getColor(this, R.color.brand_dark));
        card.addView(value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void loadHome() {
        TextView message = findViewById(R.id.homeMessage);
        Button refresh = findViewById(R.id.homeRefresh);
        refresh.setEnabled(false);
        showStatus(message, R.string.loading_home, false);
        for (int id : new int[] {R.id.homeRegistered, R.id.homeCurrent, R.id.homeReservations,
                R.id.homeAvailable, R.id.homeOccupied, R.id.homeCleaning, R.id.homeMaintenance})
            ((TextView) findViewById(id)).setText("—");
        int current = generation;
        boolean permitted = canReception();
        worker.execute(() -> {
            try {
                JSONArray[] data = new JSONArray[4];
                boolean partial = false;
                try { data[0] = api.guests(); }
                catch (Exception failure) {
                    if (isUnauthorized(failure)) throw failure;
                    partial = true;
                }
                try { data[1] = api.rooms(); }
                catch (Exception failure) {
                    if (isUnauthorized(failure)) throw failure;
                    partial = true;
                }
                try { data[2] = api.reservations(); }
                catch (Exception failure) {
                    if (isUnauthorized(failure)) throw failure;
                    partial = true;
                }
                if (permitted) try { data[3] = api.stays(); }
                catch (Exception failure) {
                    if (isUnauthorized(failure)) throw failure;
                    partial = true;
                }
                boolean incomplete = partial;
                runOnUiThread(() -> {
                    if (current != generation) return;
                    if (data[0] != null) {
                        lastGuests = data[0];
                        ((TextView) findViewById(R.id.homeRegistered)).setText(String.valueOf(data[0].length()));
                    }
                    if (data[1] != null) {
                        int[] counts = new int[5];
                        for (int i = 0; i < data[1].length(); i++) {
                            JSONObject room = data[1].optJSONObject(i);
                            if (room != null) counts[roomCategory(room)]++;
                        }
                        setHomeCount(R.id.homeAvailable, counts[RoomRules.FREE]);
                        setHomeCount(R.id.homeOccupied, counts[RoomRules.OCCUPIED]);
                        setHomeCount(R.id.homeCleaning, counts[RoomRules.CLEANING]);
                        setHomeCount(R.id.homeMaintenance, counts[RoomRules.MAINTENANCE]);
                        lastRooms = data[1];
                    }
                    if (data[2] != null)
                        ((TextView) findViewById(R.id.homeReservations)).setText(String.valueOf(data[2].length()));
                    if (data[3] != null) {
                        Set<Long> housed = new HashSet<>();
                        for (int i = 0; i < data[3].length(); i++) {
                            JSONObject stay = data[3].optJSONObject(i);
                            if (stay != null && (stay.isNull("fechaSalida") || stay.isNull("horaSalida"))) {
                                long guestId = stay.optLong("huespedId", -1);
                                if (guestId > 0) housed.add(guestId);
                            }
                        }
                        ((TextView) findViewById(R.id.homeCurrent)).setText(String.valueOf(housed.size()));
                    }
                    showStatus(message, incomplete ? R.string.dashboard_partial
                            : permitted ? R.string.empty : R.string.current_guests_restricted, incomplete);
                    refresh.setEnabled(true);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    refresh.setEnabled(true);
                    if (failure instanceof AmautaApi.ApiException
                            && ((AmautaApi.ApiException) failure).status == 401) {
                        api.clear();
                        showLogin(getString(R.string.session_expired));
                    } else showStatus(message, errorMessage(failure), true);
                });
            }
        });
    }

    private void setHomeCount(int viewId, int count) {
        ((TextView) findViewById(viewId)).setText(String.valueOf(count));
    }

    private static boolean isUnauthorized(Exception failure) {
        return failure instanceof AmautaApi.ApiException
                && ((AmautaApi.ApiException) failure).status == 401;
    }

    private void showReservations() {
        if (operationBusy || savingGuest) return;
        generation++;
        screen = RESERVATIONS;
        busy = false;
        setContentView(R.layout.activity_reservations);
        applyInsets();
        setupNavigation();
        EditText search = findViewById(R.id.reservationSearch);
        search.setText(reservationQuery);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                reservationQuery = s.toString();
                if (lastReservations != null) renderReservations();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        findViewById(R.id.newReservation).setVisibility(canReception() ? View.VISIBLE : View.GONE);
        findViewById(R.id.newReservation).setOnClickListener(view -> showReservationForm(null));
        findViewById(R.id.refreshReservations).setOnClickListener(view -> loadReservations());
        findViewById(R.id.logoutButton).setOnClickListener(view -> logout());
        loadReservations();
    }

    private void loadReservations() {
        TextView message = findViewById(R.id.reservationsMessage);
        Button refresh = findViewById(R.id.refreshReservations);
        refresh.setEnabled(false);
        ((LinearLayout) findViewById(R.id.reservationList)).removeAllViews();
        lastReservations = null;
        showStatus(message, R.string.loading_reservations, false);
        int current = generation;
        worker.execute(() -> {
            try {
                JSONArray reservations = api.reservations();
                runOnUiThread(() -> {
                    if (current != generation) return;
                    lastReservations = reservations;
                    setupReservationFilters(reservations);
                    renderReservations();
                    if (reservationNotice != 0) {
                        showStatus(message, reservationNotice, false);
                        reservationNotice = 0;
                    }
                    refresh.setEnabled(true);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    refresh.setEnabled(true);
                    if (isUnauthorized(failure)) {
                        reservationNotice = 0;
                        api.clear();
                        showLogin(getString(R.string.session_expired));
                    } else {
                        int warning = reservationNotice == R.string.reservation_saved
                                ? R.string.reservation_saved_refresh_failed
                                : reservationNotice == R.string.reservation_cancelled
                                ? R.string.reservation_cancelled_refresh_failed
                                : reservationError(failure);
                        reservationNotice = 0;
                        showStatus(message, warning, true);
                    }
                });
            }
        });
    }

    private void setupReservationFilters(JSONArray reservations) {
        List<String> options = new ArrayList<>();
        options.add(getString(R.string.reservation_all_states));
        Set<String> states = new LinkedHashSet<>();
        for (int i = 0; i < reservations.length(); i++) {
            JSONObject item = reservations.optJSONObject(i);
            if (item != null) {
                String state = item.optString("estado", "").trim();
                if (!state.isEmpty()) states.add(state);
            }
        }
        options.addAll(states);
        Spinner filter = findViewById(R.id.reservationStatusFilter);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filter.setAdapter(adapter);
        int selected = options.indexOf(reservationStatusFilter);
        filter.setSelection(Math.max(0, selected));
        if (selected < 0) reservationStatusFilter = "";
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                reservationStatusFilter = position == 0 ? "" : options.get(position);
                if (lastReservations != null) renderReservations();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void renderReservations() {
        if (screen != RESERVATIONS || lastReservations == null) return;
        LinearLayout list = findViewById(R.id.reservationList);
        TextView message = findViewById(R.id.reservationsMessage);
        list.removeAllViews();
        int shown = 0;
        for (int i = 0; i < lastReservations.length(); i++) {
            JSONObject reservation = lastReservations.optJSONObject(i);
            if (reservation == null) continue;
            String state = reservation.optString("estado", "");
            JSONObject guest = reservation.optJSONObject("huesped");
            JSONObject room = reservation.optJSONObject("habitacion");
            String guestName = guestName(guest);
            String document = guest == null ? "" : guest.optString("dni", "");
            if (!reservationStatusFilter.isEmpty() && !reservationStatusFilter.equals(state)) continue;
            if (!ReservationRules.matches(guestName, document, reservation.optLong("id", -1),
                    reservationQuery)) continue;
            shown++;
            View card = getLayoutInflater().inflate(R.layout.item_reservation, list, false);
            ((TextView) card.findViewById(R.id.reservationNumber)).setText(
                    getString(R.string.reservation_number, reservation.optLong("id")));
            ((TextView) card.findViewById(R.id.reservationState)).setText(
                    state.isEmpty() ? getString(R.string.not_available) : state);
            String roomNumber = room == null ? getString(R.string.not_available)
                    : room.optString("numero", room.optString("codigo", getString(R.string.not_available)));
            ((TextView) card.findViewById(R.id.reservationGuestRoom)).setText(
                    getString(R.string.reservation_guest_room, guestName, roomNumber));
            ((TextView) card.findViewById(R.id.reservationDates)).setText(
                    getString(R.string.reservation_dates,
                            reservation.optString("fechaIngreso", getString(R.string.not_available)),
                            reservation.optString("fechaSalida", getString(R.string.not_available))));
            Button detail = card.findViewById(R.id.viewReservation);
            long id = reservation.optLong("id", -1);
            detail.setEnabled(id > 0);
            detail.setOnClickListener(view -> showReservationDetail(id));
            list.addView(card);
        }
        showStatus(message, lastReservations.length() == 0 ? R.string.no_reservations
                : shown == 0 ? R.string.no_filtered_reservations : R.string.empty, false);
    }

    private String guestName(JSONObject guest) {
        if (guest == null) return getString(R.string.not_available);
        String name = (guest.optString("nombre", "") + " " + guest.optString("apellido", "")).trim();
        return name.isEmpty() ? getString(R.string.not_available) : name;
    }

    private void showReservationForm(JSONObject existing) {
        if (operationBusy || !canReception()) return;
        generation++;
        screen = RESERVATION_FORM;
        editingReservation = existing;
        reservationGuestId = existing == null ? -1 : nestedId(existing, "huesped");
        reservationRoomId = existing == null ? -1 : nestedId(existing, "habitacion");
        reservationEntry = existing == null ? "" : existing.optString("fechaIngreso", "");
        reservationExit = existing == null ? "" : existing.optString("fechaSalida", "");
        reservationState = existing == null ? "RESERVADO" : existing.optString("estado", "RESERVADO");
        reservationGuests = null;
        reservationRooms = null;
        reservationChoices = null;
        reservationGuestIds.clear();
        reservationRoomIds.clear();
        setContentView(R.layout.activity_reservation_form);
        applyInsets();
        setupNavigation();
        ((TextView) findViewById(R.id.reservationFormTitle)).setText(existing == null
                ? R.string.reservation_form_new : R.string.reservation_form_edit);
        ((TextView) findViewById(R.id.reservationFormState)).setText(
                getString(R.string.reservation_state_label, reservationState));
        if (existing != null) setupEditableReservationState();
        updateReservationDates();
        findViewById(R.id.reservationEntry).setOnClickListener(view -> pickReservationDate(true));
        findViewById(R.id.reservationExit).setOnClickListener(view -> pickReservationDate(false));
        findViewById(R.id.cancelReservationForm).setOnClickListener(view -> showReservations());
        findViewById(R.id.saveReservation).setOnClickListener(view -> saveReservation());
        loadReservationOptions();
    }

    private void setupEditableReservationState() {
        Spinner state = findViewById(R.id.reservationEditState);
        state.setVisibility(View.VISIBLE);
        List<String> options = new ArrayList<>();
        options.add("RESERVADO");
        options.add("CONFIRMADO");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        state.setAdapter(adapter);
        state.setSelection(Math.max(0, options.indexOf(reservationState)));
        state.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                reservationState = options.get(position);
                ((TextView) findViewById(R.id.reservationFormState)).setText(
                        getString(R.string.reservation_state_label, reservationState));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private static long nestedId(JSONObject item, String key) {
        JSONObject nested = item.optJSONObject(key);
        return nested == null ? -1 : nested.optLong("id", -1);
    }

    private void pickReservationDate(boolean entry) {
        Calendar initial = Calendar.getInstance();
        String value = entry ? reservationEntry : reservationExit;
        if (ReservationRules.validDate(value)) {
            initial.set(Integer.parseInt(value.substring(0, 4)),
                    Integer.parseInt(value.substring(5, 7)) - 1,
                    Integer.parseInt(value.substring(8, 10)));
        }
        new DatePickerDialog(this, (picker, year, month, day) -> {
            String selected = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day);
            if (entry) reservationEntry = selected;
            else reservationExit = selected;
            updateReservationDates();
            if (reservationRooms != null && reservationChoices != null) populateReservationRooms();
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH),
                initial.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateReservationDates() {
        ((Button) findViewById(R.id.reservationEntry)).setText(reservationEntry.isEmpty()
                ? getString(R.string.select_entry_date) : reservationEntry);
        ((Button) findViewById(R.id.reservationExit)).setText(reservationExit.isEmpty()
                ? getString(R.string.select_exit_date) : reservationExit);
        updateReservationSaveEnabled();
    }

    private void loadReservationOptions() {
        TextView message = findViewById(R.id.reservationFormMessage);
        showStatus(message, R.string.reservation_loading_options, false);
        int current = generation;
        worker.execute(() -> {
            try {
                JSONArray guests = api.guests();
                JSONArray rooms = api.rooms();
                JSONArray reservations = api.reservations();
                runOnUiThread(() -> {
                    if (current != generation) return;
                    reservationGuests = guests;
                    reservationRooms = rooms;
                    reservationChoices = reservations;
                    populateReservationGuests();
                    populateReservationRooms();
                    showStatus(message, guests.length() == 0 ? R.string.reservation_no_guests
                            : ReservationRules.validRange(reservationEntry, reservationExit)
                            && reservationRoomIds.size() == 1 ? R.string.reservation_no_rooms
                            : R.string.empty, false);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    if (isUnauthorized(failure)) {
                        api.clear();
                        showLogin(getString(R.string.session_expired));
                    } else showStatus(message, reservationError(failure), true);
                });
            }
        });
    }

    private void populateReservationGuests() {
        reservationGuestIds.clear();
        List<String> labels = new ArrayList<>();
        reservationGuestIds.add(-1L);
        labels.add(getString(R.string.reservation_select_guest));
        for (int i = 0; i < reservationGuests.length(); i++) {
            JSONObject guest = reservationGuests.optJSONObject(i);
            if (guest == null || guest.optLong("id", -1) <= 0) continue;
            reservationGuestIds.add(guest.optLong("id"));
            labels.add(getString(R.string.reservation_guest_choice,
                    guestName(guest), guest.optString("dni", "")));
        }
        Spinner spinner = findViewById(R.id.reservationGuest);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int selection = reservationGuestIds.indexOf(reservationGuestId);
        spinner.setSelection(Math.max(0, selection));
        if (selection < 0) reservationGuestId = -1;
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                reservationGuestId = reservationGuestIds.get(position);
                updateReservationSaveEnabled();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        updateReservationSaveEnabled();
    }

    private void populateReservationRooms() {
        long desired = reservationRoomId;
        reservationRoomIds.clear();
        List<String> labels = new ArrayList<>();
        reservationRoomIds.add(-1L);
        boolean datesValid = ReservationRules.validRange(reservationEntry, reservationExit);
        labels.add(getString(datesValid ? R.string.reservation_select_room
                : R.string.reservation_select_dates_first));
        if (datesValid) for (int i = 0; i < reservationRooms.length(); i++) {
            JSONObject room = reservationRooms.optJSONObject(i);
            if (room == null || room.optLong("id", -1) <= 0) continue;
            long id = room.optLong("id");
            boolean unchanged = editingReservation != null && id == nestedId(editingReservation, "habitacion")
                    && reservationEntry.equals(editingReservation.optString("fechaIngreso"))
                    && reservationExit.equals(editingReservation.optString("fechaSalida"));
            boolean free = RoomRules.category(room.optString("estado"),
                    room.optString("estadoOperativo"), !room.isNull("limpiezaPendienteDesde")) == RoomRules.FREE;
            if (!unchanged && !free) continue;
            if (!unchanged && reservationOverlaps(id)) continue;
            reservationRoomIds.add(id);
            labels.add(getString(R.string.reservation_room_choice,
                    room.optString("numero", room.optString("codigo", "")),
                    room.optString("tipo", "")));
        }
        Spinner spinner = findViewById(R.id.reservationRoom);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int selection = reservationRoomIds.indexOf(desired);
        spinner.setSelection(Math.max(0, selection));
        reservationRoomId = selection < 0 ? -1 : desired;
        spinner.setEnabled(datesValid && reservationRoomIds.size() > 1);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                reservationRoomId = reservationRoomIds.get(position);
                updateReservationSaveEnabled();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        TextView message = findViewById(R.id.reservationFormMessage);
        if (datesValid && reservationRoomIds.size() == 1)
            showStatus(message, R.string.reservation_no_rooms, false);
        else showStatus(message, R.string.empty, false);
        updateReservationSaveEnabled();
    }

    private boolean reservationOverlaps(long roomId) {
        for (int i = 0; i < reservationChoices.length(); i++) {
            JSONObject item = reservationChoices.optJSONObject(i);
            if (item == null || item.optLong("id", -1) == (editingReservation == null
                    ? -1 : editingReservation.optLong("id", -1))) continue;
            if ("CANCELADO".equalsIgnoreCase(item.optString("estado"))) continue;
            if (nestedId(item, "habitacion") == roomId && ReservationRules.overlaps(
                    reservationEntry, reservationExit, item.optString("fechaIngreso"),
                    item.optString("fechaSalida"))) return true;
        }
        return false;
    }

    private void updateReservationSaveEnabled() {
        if (screen != RESERVATION_FORM) return;
        findViewById(R.id.saveReservation).setEnabled(!operationBusy && reservationGuests != null
                && reservationRooms != null && reservationChoices != null
                && reservationGuestId > 0 && reservationRoomId > 0
                && ReservationRules.validRange(reservationEntry, reservationExit));
    }

    private void saveReservation() {
        if (operationBusy || screen != RESERVATION_FORM) return;
        TextView message = findViewById(R.id.reservationFormMessage);
        if (reservationGuestId <= 0 || reservationRoomId <= 0) {
            showStatus(message, R.string.reservation_missing_fields, true);
            return;
        }
        if (!ReservationRules.validRange(reservationEntry, reservationExit)) {
            showStatus(message, R.string.reservation_invalid_dates, true);
            return;
        }
        if (!reservationRoomIds.contains(reservationRoomId)) {
            showStatus(message, R.string.reservation_conflict, true);
            return;
        }
        long id = editingReservation == null ? -1 : editingReservation.optLong("id", -1);
        if (editingReservation != null && id <= 0) {
            showStatus(message, R.string.reservation_invalid_id, true);
            return;
        }
        operationBusy = true;
        findViewById(R.id.saveReservation).setEnabled(false);
        findViewById(R.id.cancelReservationForm).setEnabled(false);
        showStatus(message, R.string.reservation_saving, false);
        long guestId = reservationGuestId, roomId = reservationRoomId;
        String entry = reservationEntry, exit = reservationExit, state = reservationState;
        int current = generation;
        worker.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("huesped", new JSONObject().put("id", guestId));
                body.put("habitacion", new JSONObject().put("id", roomId));
                body.put("fechaIngreso", entry);
                body.put("fechaSalida", exit);
                body.put("estado", state);
                if (editingReservation != null && !editingReservation.isNull("fechaReserva"))
                    body.put("fechaReserva", editingReservation.opt("fechaReserva"));
                api.saveReservation(id, body);
                runOnUiThread(() -> {
                    if (current != generation) return;
                    operationBusy = false;
                    reservationNotice = R.string.reservation_saved;
                    showReservations();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    operationBusy = false;
                    if (isUnauthorized(failure)) {
                        api.clear();
                        showLogin(getString(R.string.session_expired));
                    } else {
                        findViewById(R.id.cancelReservationForm).setEnabled(true);
                        updateReservationSaveEnabled();
                        showStatus(message, reservationError(failure), true);
                    }
                });
            }
        });
    }

    private void showReservationDetail(long id) {
        if (id <= 0 || operationBusy) return;
        generation++;
        screen = RESERVATION_DETAIL;
        currentReservation = null;
        setContentView(R.layout.activity_reservation_detail);
        applyInsets();
        setupNavigation();
        ((TextView) findViewById(R.id.reservationDetailTitle)).setText(
                getString(R.string.reservation_detail_title, id));
        findViewById(R.id.backReservations).setOnClickListener(view -> showReservations());
        TextView message = findViewById(R.id.reservationDetailMessage);
        showStatus(message, R.string.reservation_loading_detail, false);
        int current = generation;
        worker.execute(() -> {
            try {
                JSONObject reservation = api.reservation(id);
                runOnUiThread(() -> {
                    if (current != generation) return;
                    currentReservation = reservation;
                    renderReservationDetail(reservation);
                    if (canReception()) showStatus(message, R.string.empty, false);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    if (isUnauthorized(failure)) {
                        api.clear();
                        showLogin(getString(R.string.session_expired));
                    } else showStatus(message, reservationError(failure), true);
                });
            }
        });
    }

    private void renderReservationDetail(JSONObject reservation) {
        LinearLayout fields = findViewById(R.id.reservationDetailFields);
        fields.removeAllViews();
        JSONObject guest = reservation.optJSONObject("huesped");
        JSONObject room = reservation.optJSONObject("habitacion");
        String roomNumber = room == null ? getString(R.string.not_available)
                : room.optString("numero", room.optString("codigo", getString(R.string.not_available)));
        addReservationDetailField(fields, R.string.reservation_guest_label, guestName(guest));
        if (guest != null) addReservationDetailField(fields, R.string.guest_document,
                guest.optString("dni", getString(R.string.not_available)));
        addReservationDetailField(fields, R.string.reservation_room_label, roomNumber);
        addReservationDetailField(fields, R.string.reservation_entry_label,
                reservation.optString("fechaIngreso", getString(R.string.not_available)));
        addReservationDetailField(fields, R.string.reservation_exit_label,
                reservation.optString("fechaSalida", getString(R.string.not_available)));
        addReservationDetailField(fields, R.string.room_detail_state,
                reservation.optString("estado", getString(R.string.not_available)));
        String state = reservation.optString("estado", "").trim().toUpperCase(Locale.ROOT);
        boolean mutable = canReception() && (state.equals("RESERVADO") || state.equals("CONFIRMADO"));
        Button edit = findViewById(R.id.editReservation);
        Button cancel = findViewById(R.id.cancelReservation);
        edit.setEnabled(mutable);
        cancel.setEnabled(mutable);
        Button checkIn = findViewById(R.id.checkInReservation);
        checkIn.setVisibility(mutable ? View.VISIBLE : View.GONE);
        checkIn.setOnClickListener(view -> startActivityForResult(
                new Intent(this, ReceptionActivity.class)
                        .putExtra("reservationId", reservation.optLong("id", -1)), 620));
        edit.setOnClickListener(view -> showReservationForm(reservation));
        cancel.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.cancel_reservation)
                .setMessage(getString(R.string.reservation_cancel_question,
                        reservation.optLong("id", -1)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> cancelReservation())
                .show());
        if (!canReception()) showStatus(findViewById(R.id.reservationDetailMessage),
                R.string.reservation_restricted, false);
    }

    private void addReservationDetailField(LinearLayout fields, int labelId, String value) {
        TextView row = new TextView(this);
        row.setText(getString(R.string.guest_detail_field, getString(labelId), value));
        row.setTextSize(16);
        row.setTextColor(ContextCompat.getColor(this, R.color.ink));
        row.setPadding(0, dp(10), 0, dp(10));
        fields.addView(row);
    }

    private void cancelReservation() {
        if (operationBusy || currentReservation == null) return;
        long id = currentReservation.optLong("id", -1);
        if (id <= 0) return;
        operationBusy = true;
        findViewById(R.id.editReservation).setEnabled(false);
        findViewById(R.id.cancelReservation).setEnabled(false);
        findViewById(R.id.backReservations).setEnabled(false);
        TextView message = findViewById(R.id.reservationDetailMessage);
        showStatus(message, R.string.reservation_cancelling, false);
        int current = generation;
        worker.execute(() -> {
            try {
                api.cancelReservation(id, currentReservation);
                runOnUiThread(() -> {
                    if (current != generation) return;
                    operationBusy = false;
                    reservationNotice = R.string.reservation_cancelled;
                    showReservations();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    operationBusy = false;
                    if (isUnauthorized(failure)) {
                        api.clear();
                        showLogin(getString(R.string.session_expired));
                    } else {
                        findViewById(R.id.editReservation).setEnabled(true);
                        findViewById(R.id.cancelReservation).setEnabled(true);
                        findViewById(R.id.backReservations).setEnabled(true);
                        showStatus(message, reservationError(failure), true);
                    }
                });
            }
        });
    }

    private int reservationError(Exception failure) {
        if (failure instanceof IOException) return R.string.connection_error;
        if (failure instanceof AmautaApi.ApiException) {
            int status = ((AmautaApi.ApiException) failure).status;
            if (status == 400) return R.string.reservation_server_rejected;
            if (status == 401) return R.string.session_expired;
            if (status == 403) return R.string.operation_denied;
            if (status == 404) return R.string.reservation_not_found;
            if (status == 409) return R.string.reservation_conflict;
            if (status == 426) return R.string.reservation_backend_restart;
        }
        return R.string.server_error;
    }

    private void showRooms() {
        if (operationBusy) return;
        generation++;
        screen = ROOMS;
        busy = false;
        selectedRoomId = -1;
        setContentView(R.layout.activity_rooms);
        applyInsets();
        setupNavigation();
        findViewById(R.id.refreshButton).setOnClickListener(view -> loadRooms());
        findViewById(R.id.logoutButton).setOnClickListener(view -> logout());
        RadioGroup filters = findViewById(R.id.roomFilters);
        int[] ids = {R.id.filterAll, R.id.filterFree, R.id.filterOccupied,
                R.id.filterCleaning, R.id.filterMaintenance};
        filters.check(ids[roomFilter]);
        filters.setOnCheckedChangeListener((group, checkedId) -> {
            for (int i = 0; i < ids.length; i++) if (ids[i] == checkedId) roomFilter = i;
            if (lastRooms != null) renderRooms(lastRooms);
        });
        if (lastRooms != null) renderRooms(lastRooms);
        if (lastRooms == null || roomRefreshRequired) loadRooms();
    }

    private void loadRooms() {
        TextView message = findViewById(R.id.roomsMessage);
        Button refresh = findViewById(R.id.refreshButton);
        refresh.setEnabled(false);
        showStatus(message, R.string.loading_rooms, false);
        int current = generation;
        worker.execute(() -> {
            try {
                JSONArray rooms = api.rooms();
                runOnUiThread(() -> {
                    if (current != generation) return;
                    refresh.setEnabled(true);
                    lastRooms = rooms;
                    roomRefreshRequired = false;
                    renderRooms(rooms);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    refresh.setEnabled(true);
                    if (failure instanceof AmautaApi.ApiException
                            && ((AmautaApi.ApiException) failure).status == 401) {
                        api.clear();
                        showLogin(getString(R.string.session_expired));
                    } else showStatus(message, errorMessage(failure), true);
                });
            }
        });
    }

    private void renderRooms(JSONArray rooms) {
        LinearLayout list = findViewById(R.id.roomList);
        TextView message = findViewById(R.id.roomsMessage);
        TextView totals = findViewById(R.id.roomTotals);
        list.removeAllViews();
        int available = 0;
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject room = rooms.optJSONObject(i);
            if (room == null) continue;
            sorted.add(room);
            if (roomCategory(room) == RoomRules.FREE) available++;
        }
        sorted.sort((a, b) -> {
            int byNumber = RoomRules.compareNumbers(a.optString("numero"), b.optString("numero"));
            return byNumber != 0 ? byNumber : Long.compare(a.optLong("id"), b.optLong("id"));
        });
        int shown = 0;
        for (JSONObject room : sorted) {
            if (roomFilter != RoomRules.ALL && roomCategory(room) != roomFilter) continue;
            shown++;
            String state = room.optString("estadoOperativo", "");
            if (state.isEmpty() || state.equals("null")) state = room.optString("estado", "Sin estado");
            View row = getLayoutInflater().inflate(R.layout.item_room, list, false);
            ((TextView) row.findViewById(R.id.roomNumber)).setText(getString(R.string.room_number,
                    room.optString("numero", room.optString("codigo", "—"))));
            TextView stateView = row.findViewById(R.id.roomState);
            stateView.setText(state);
            int category = roomCategory(room);
            int foreground = category == RoomRules.FREE ? R.color.free_text
                    : category == RoomRules.OCCUPIED ? R.color.occupied_text
                    : category == RoomRules.CLEANING ? R.color.cleaning_text
                    : category == RoomRules.MAINTENANCE ? R.color.maintenance_text : R.color.brand;
            int background = category == RoomRules.FREE ? R.color.free_surface
                    : category == RoomRules.OCCUPIED ? R.color.occupied_surface
                    : category == RoomRules.CLEANING ? R.color.cleaning_surface
                    : category == RoomRules.MAINTENANCE ? R.color.maintenance_surface : R.color.info_surface;
            stateView.setTextColor(ContextCompat.getColor(this, foreground));
            stateView.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, background)));
            ((TextView) row.findViewById(R.id.roomType)).setText(room.optString("tipo", ""));
            long roomId = room.optLong("id", -1);
            if (roomId > 0) {
                row.setContentDescription(getString(R.string.open_room_detail,
                        room.optString("numero", room.optString("codigo", ""))));
                row.setOnClickListener(view -> showRoomDetail(roomId));
            }
            list.addView(row);
        }
        totals.setText(getString(available == 1 ? R.string.room_totals_one
                : R.string.room_totals, available, rooms.length()));
        showStatus(message, rooms.length() == 0 ? R.string.no_rooms
                : shown == 0 ? R.string.no_filtered_rooms : R.string.empty, false);
    }

    private int roomCategory(JSONObject room) {
        return RoomRules.category(room.optString("estado"), room.optString("estadoOperativo"),
                !room.isNull("limpiezaPendienteDesde"));
    }

    private boolean canReception() {
        String role = currentRole.trim().toUpperCase(Locale.ROOT);
        return role.equals("ADMIN") || role.equals("ADMINISTRADOR") || role.equals("JEFE")
                || role.equals("RECEPCION") || role.equals("RECEPCIONISTA")
                || role.equals("RECEPCION_CAJA");
    }

    private void openForRole() {
        String role = currentRole.trim().toUpperCase(Locale.ROOT);
        if (role.equals("LIMPIEZA") || role.equals("PERSONAL_LIMPIEZA")) {
            startActivity(new Intent(this, CleaningActivity.class).putExtra("cleanerOnly", true));
            finish();
        } else if (canReception()) showHome();
        else {
            api.clear();
            showLogin(getString(R.string.operation_denied));
        }
    }

    private boolean canAdmin() {
        String role = currentRole.trim().toUpperCase(Locale.ROOT);
        return role.equals("ADMIN") || role.equals("ADMINISTRADOR") || role.equals("JEFE");
    }

    private void showGuests(boolean refresh) {
        if (operationBusy || savingGuest) return;
        generation++;
        screen = GUESTS;
        if (refresh) lastGuests = null;
        selectedRoomId = -1;
        setContentView(R.layout.activity_guests);
        applyInsets();
        setupNavigation();
        EditText search = findViewById(R.id.guestSearch);
        search.setText(guestQuery);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                guestQuery = s.toString();
                if (lastGuests != null) renderGuests(lastGuests);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        findViewById(R.id.addGuest).setOnClickListener(view -> showGuestForm(null));
        findViewById(R.id.refreshGuests).setOnClickListener(view -> loadGuests());
        if (lastGuests != null) renderGuests(lastGuests);
        if (refresh || lastGuests == null) loadGuests();
    }

    private void loadGuests() {
        TextView message = findViewById(R.id.guestMessage);
        Button refresh = findViewById(R.id.refreshGuests);
        refresh.setEnabled(false);
        showStatus(message, R.string.loading_guests, false);
        int current = generation;
        worker.execute(() -> {
            try {
                JSONArray guests = api.guests();
                runOnUiThread(() -> {
                    if (current != generation) return;
                    refresh.setEnabled(true);
                    lastGuests = guests;
                    renderGuests(guests);
                    if (guestNotice != 0) {
                        showStatus(message, guestNotice, false);
                        guestNotice = 0;
                    }
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    refresh.setEnabled(true);
                    if (failure instanceof AmautaApi.ApiException
                            && ((AmautaApi.ApiException) failure).status == 401) {
                        guestNotice = 0;
                        api.clear();
                        showLogin(getString(R.string.session_expired));
                    } else {
                        int failedRefresh = guestNotice == R.string.guest_deleted
                                ? R.string.guest_deleted_refresh_failed
                                : guestNotice == R.string.guest_saved
                                ? R.string.guest_saved_refresh_failed : errorMessage(failure);
                        guestNotice = 0;
                        showStatus(message, failedRefresh, true);
                    }
                });
            }
        });
    }

    private void renderGuests(JSONArray guests) {
        LinearLayout list = findViewById(R.id.guestList);
        TextView message = findViewById(R.id.guestMessage);
        ((TextView) findViewById(R.id.guestTotal)).setText(
                getResources().getQuantityString(R.plurals.guest_total_count,
                        guests.length(), guests.length()));
        list.removeAllViews();
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < guests.length(); i++) {
            JSONObject guest = guests.optJSONObject(i);
            if (guest != null) sorted.add(guest);
        }
        sorted.sort((a, b) -> GuestRules.compare(a.optString("apellido"), a.optString("nombre"),
                a.optString("dni"), b.optString("apellido"), b.optString("nombre"), b.optString("dni")));
        int shown = 0;
        for (JSONObject guest : sorted) {
            String name = guest.optString("nombre", "");
            String surname = guest.optString("apellido", "");
            String document = guest.optString("dni", "");
            if (!GuestRules.matches(name, surname, document, guestQuery)) continue;
            shown++;
            View item = getLayoutInflater().inflate(R.layout.item_guest, list, false);
            ((TextView) item.findViewById(R.id.guestName)).setText((surname + ", " + name).trim());
            ((TextView) item.findViewById(R.id.guestDocument)).setText(
                    getString(R.string.guest_document_label, document));
            Button edit = item.findViewById(R.id.editGuest);
            edit.setEnabled(guest.optLong("id", -1) > 0);
            edit.setOnClickListener(view -> showGuestForm(guest));
            Button view = item.findViewById(R.id.viewGuest);
            view.setOnClickListener(button -> showGuestDetail(guest));
            list.addView(item);
        }
        showStatus(message, guests.length() == 0 ? R.string.no_guests
                : shown == 0 ? R.string.no_search_results : R.string.empty, false);
    }

    private static final int[] GUEST_INPUTS = {R.id.guestFirstName, R.id.guestLastName,
            R.id.guestDni, R.id.guestOrigin, R.id.guestCountry, R.id.guestProfession, R.id.guestPhone};
    private static final String[] GUEST_KEYS = {"nombre", "apellido", "dni", "procedencia",
            "pais", "profesion", "telefono"};
    private static final int[] GUEST_LABELS = {R.string.guest_first_name, R.string.guest_last_name,
            R.string.guest_document, R.string.guest_origin, R.string.guest_country,
            R.string.guest_profession, R.string.guest_phone};

    private void showGuestDetail(JSONObject guest) {
        if (savingGuest || operationBusy) return;
        generation++;
        screen = GUEST_DETAIL;
        setContentView(R.layout.activity_guest_detail);
        applyInsets();
        setupNavigation();
        ((TextView) findViewById(R.id.detailGuestName)).setText(
                (guest.optString("nombre", "") + " " + guest.optString("apellido", "")).trim());
        LinearLayout fields = findViewById(R.id.guestDetailFields);
        for (int i = 0; i < GUEST_KEYS.length; i++) {
            TextView field = new TextView(this);
            String value = guest.optString(GUEST_KEYS[i], "").trim();
            field.setText(getString(R.string.guest_detail_field, getString(GUEST_LABELS[i]),
                    value.isEmpty() || value.equals("null") ? getString(R.string.guest_field_missing) : value));
            field.setTextSize(16);
            field.setTextColor(ContextCompat.getColor(this, R.color.ink));
            field.setPadding(0, dp(10), 0, dp(10));
            fields.addView(field);
        }
        long id = guest.optLong("id", -1);
        Button edit = findViewById(R.id.detailEditGuest);
        Button delete = findViewById(R.id.detailDeleteGuest);
        edit.setEnabled(id > 0);
        delete.setEnabled(id > 0);
        edit.setOnClickListener(view -> showGuestForm(guest));
        delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.delete_guest)
                .setMessage(getString(R.string.delete_guest_question,
                        (guest.optString("nombre", "") + " " + guest.optString("apellido", "")).trim()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_guest, (dialog, which) -> deleteGuest(id))
                .show());
        findViewById(R.id.detailBackGuests).setOnClickListener(view -> showGuests(false));
    }

    private void deleteGuest(long id) {
        if (operationBusy || id <= 0) return;
        operationBusy = true;
        TextView message = findViewById(R.id.guestDetailMessage);
        findViewById(R.id.detailEditGuest).setEnabled(false);
        findViewById(R.id.detailDeleteGuest).setEnabled(false);
        findViewById(R.id.detailBackGuests).setEnabled(false);
        showStatus(message, R.string.deleting_guest, false);
        int current = generation;
        worker.execute(() -> {
            try {
                api.deleteGuest(id);
                runOnUiThread(() -> {
                    if (current != generation) return;
                    operationBusy = false;
                    lastGuests = null;
                    guestNotice = R.string.guest_deleted;
                    showGuests(true);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    operationBusy = false;
                    if (isUnauthorized(failure)) {
                        api.clear();
                        showLogin(getString(R.string.session_expired));
                        return;
                    }
                    findViewById(R.id.detailEditGuest).setEnabled(true);
                    findViewById(R.id.detailDeleteGuest).setEnabled(true);
                    findViewById(R.id.detailBackGuests).setEnabled(true);
                    boolean rejected = failure instanceof AmautaApi.ApiException
                            && (((AmautaApi.ApiException) failure).status == 409
                            || ((AmautaApi.ApiException) failure).status == 500);
                    showStatus(message, rejected ? getString(R.string.guest_delete_rejected)
                            : operationError(failure), true);
                });
            }
        });
    }

    private void showGuestForm(JSONObject guest) {
        if (savingGuest) return;
        generation++;
        screen = GUEST_FORM;
        editingGuestId = guest == null ? -1 : guest.optLong("id", -1);
        setContentView(R.layout.activity_guest_form);
        applyInsets();
        setupNavigation();
        ((TextView) findViewById(R.id.guestFormTitle)).setText(guest == null
                ? R.string.guest_create_title : R.string.guest_edit_title);
        for (int i = 0; i < GUEST_INPUTS.length; i++) {
            ((EditText) findViewById(GUEST_INPUTS[i])).setText(
                    guest == null ? "" : guest.optString(GUEST_KEYS[i], ""));
        }
        findViewById(R.id.cancelGuest).setOnClickListener(view -> showGuests(false));
        findViewById(R.id.saveGuest).setOnClickListener(view -> saveGuest());
    }

    private void saveGuest() {
        if (savingGuest) return;
        String[] values = new String[GUEST_INPUTS.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = ((EditText) findViewById(GUEST_INPUTS[i])).getText().toString().trim();
        }
        TextView message = findViewById(R.id.guestFormMessage);
        String validation = GuestRules.validate(values[0], values[1], values[2],
                values[3], values[4], values[5], values[6]);
        if (validation != null) {
            int field = values[0].isEmpty() || values[0].length() > 255 ? 0
                    : values[1].isEmpty() || values[1].length() > 255 ? 1
                    : !values[2].matches("[A-Za-z0-9-]{5,20}") ? 2
                    : values[3].length() > 255 ? 3
                    : values[4].length() > 255 ? 4
                    : values[5].length() > 255 ? 5 : 6;
            EditText input = findViewById(GUEST_INPUTS[field]);
            input.setError(validation);
            input.requestFocus();
            showStatus(message, "", false);
            return;
        }
        if (screen != GUEST_FORM || (editingGuestId == 0)) return;
        savingGuest = true;
        findViewById(R.id.saveGuest).setEnabled(false);
        findViewById(R.id.cancelGuest).setEnabled(false);
        showStatus(message, R.string.saving_guest, false);
        long guestId = editingGuestId;
        int current = generation;
        worker.execute(() -> {
            try {
                JSONArray latest = api.guests();
                for (int i = 0; i < latest.length(); i++) {
                    JSONObject item = latest.optJSONObject(i);
                    if (item != null && item.optString("dni", "").equalsIgnoreCase(values[2])
                            && item.optLong("id", -1) != guestId) throw new DuplicateGuestException();
                }
                try {
                    JSONObject exact = api.guestByDocument(values[2]);
                    if (exact.optLong("id", -1) != guestId) throw new DuplicateGuestException();
                } catch (AmautaApi.ApiException missing) {
                    if (missing.status != 404) throw missing;
                }
                JSONObject data = new JSONObject();
                for (int i = 0; i < GUEST_KEYS.length; i++) data.put(GUEST_KEYS[i], values[i]);
                api.saveGuest(guestId, data);
                runOnUiThread(() -> {
                    if (current != generation) return;
                    savingGuest = false;
                    lastGuests = null;
                    guestNotice = R.string.guest_saved;
                    showGuests(true);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    savingGuest = false;
                    findViewById(R.id.saveGuest).setEnabled(true);
                    findViewById(R.id.cancelGuest).setEnabled(true);
                    if (failure instanceof AmautaApi.ApiException
                            && ((AmautaApi.ApiException) failure).status == 401) {
                        api.clear();
                        showLogin(getString(R.string.session_expired));
                    } else {
                        boolean duplicate = failure instanceof DuplicateGuestException
                            || (failure instanceof AmautaApi.ApiException
                            && ((AmautaApi.ApiException) failure).status == 409)
                            ;
                        if (duplicate) ((EditText) findViewById(R.id.guestDni))
                                .setError(getString(R.string.guest_duplicate));
                        showStatus(message, duplicate ? getString(R.string.guest_duplicate)
                                : operationError(failure), true);
                    }
                });
            }
        });
    }

    private static final class DuplicateGuestException extends Exception { }

    private void showRoomDetail(long roomId) {
        generation++;
        screen = ROOM_DETAIL;
        selectedRoomId = roomId;
        operationBusy = false;
        setContentView(R.layout.activity_room_detail);
        applyInsets();
        setupNavigation();
        findViewById(R.id.backToRoomsButton).setOnClickListener(view -> showRooms());
        findViewById(R.id.retryDetailButton).setOnClickListener(view -> loadRoomDetail());
        loadRoomDetail();
    }

    private void loadRoomDetail() {
        TextView message = findViewById(R.id.detailMessage);
        Button retry = findViewById(R.id.retryDetailButton);
        retry.setEnabled(false);
        retry.setVisibility(View.GONE);
        findViewById(R.id.completeCleaningButton).setVisibility(View.GONE);
        findViewById(R.id.finishMaintenanceButton).setVisibility(View.GONE);
        ((LinearLayout) findViewById(R.id.activeStaysList)).removeAllViews();
        ((LinearLayout) findViewById(R.id.historyList)).removeAllViews();
        showStatus(message, R.string.loading_room_detail, false);
        long roomId = selectedRoomId;
        int current = generation;
        boolean permitted = canReception();
        worker.execute(() -> {
            try {
                JSONArray rooms = api.rooms();
                JSONObject selected = null;
                for (int i = 0; i < rooms.length(); i++) {
                    JSONObject room = rooms.optJSONObject(i);
                    if (room != null && room.optLong("id", -1) == roomId) {
                        selected = room;
                        break;
                    }
                }
                JSONArray stays = selected != null && permitted ? api.stays() : new JSONArray();
                JSONArray movements = selected != null && permitted
                        ? api.roomMovements(roomId) : new JSONArray();
                JSONObject result = selected;
                runOnUiThread(() -> {
                    if (current != generation) return;
                    retry.setEnabled(true);
                    lastRooms = rooms;
                    operationBusy = false;
                    roomRefreshRequired = false;
                    if (result == null) {
                        showStatus(message, R.string.room_not_found, true);
                        retry.setVisibility(View.VISIBLE);
                    }
                    else renderRoomDetail(result, stays, movements);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    retry.setEnabled(true);
                    retry.setVisibility(View.VISIBLE);
                    operationBusy = false;
                    if (failure instanceof AmautaApi.ApiException
                            && ((AmautaApi.ApiException) failure).status == 401) {
                        api.clear();
                        lastRooms = null;
                        showLogin(getString(R.string.session_expired));
                    } else showStatus(message, roomRefreshRequired
                            ? R.string.operation_done_refresh_failed : errorMessage(failure), true);
                });
            }
        });
    }

    private void renderRoomDetail(JSONObject room, JSONArray stays, JSONArray movements) {
        ((TextView) findViewById(R.id.detailNumber)).setText(value(room, "numero"));
        ((TextView) findViewById(R.id.detailType)).setText(value(room, "tipo"));
        ((TextView) findViewById(R.id.detailCapacity)).setText(value(room, "capacidad"));
        String operational = room.optString("estadoOperativo", "");
        ((TextView) findViewById(R.id.detailState)).setText(operational.isEmpty()
                || operational.equals("null") ? value(room, "estado") : operational);
        String price = value(room, "precio");
        if (!room.isNull("precio")) {
            try {
                NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("es", "PE"));
                price = currency.format(new BigDecimal(room.getString("precio")));
            } catch (Exception ignored) { /* Show the raw backend value. */ }
        }
        ((TextView) findViewById(R.id.detailPrice)).setText(price);
        LinearLayout activeList = findViewById(R.id.activeStaysList);
        LinearLayout historyList = findViewById(R.id.historyList);
        activeList.removeAllViews();
        historyList.removeAllViews();
        if (!canReception()) {
            showStatus(findViewById(R.id.detailMessage), R.string.restricted_room_actions, false);
            findViewById(R.id.activeStaysTitle).setVisibility(View.GONE);
            findViewById(R.id.historyTitle).setVisibility(View.GONE);
            return;
        }
        findViewById(R.id.activeStaysTitle).setVisibility(View.VISIBLE);
        findViewById(R.id.historyTitle).setVisibility(View.VISIBLE);
        int active = 0;
        for (int i = 0; i < stays.length(); i++) {
            JSONObject stay = stays.optJSONObject(i);
            if (stay == null || stay.optLong("habitacionId", -1) != selectedRoomId
                    || (!stay.isNull("fechaSalida") && !stay.isNull("horaSalida"))) continue;
            active++;
            View item = getLayoutInflater().inflate(R.layout.item_active_stay, activeList, false);
            long stayId = stay.optLong("id", -1);
            String guest = stay.optString("nombreHuesped", "");
            if (guest.isEmpty() || guest.equals("null")) guest = getString(R.string.not_available);
            ((TextView) item.findViewById(R.id.stayLabel)).setText(
                    getString(R.string.stay_label, stayId, guest));
            Button confirm = item.findViewById(R.id.confirmDepartureButton);
            confirm.setEnabled(stayId > 0);
            confirm.setOnClickListener(view -> confirmOperation(R.string.confirm_departure_question,
                    () -> api.confirmDeparture(stayId)));
            activeList.addView(item);
        }
        if (active == 0) addInfo(activeList, R.string.no_active_stays);
        String state = room.optString("estado", "").trim().toUpperCase(Locale.ROOT);
        Button cleaning = findViewById(R.id.completeCleaningButton);
        cleaning.setVisibility(active == 0 && canAdmin() && state.equals("PENDIENTE DE LIMPIEZA")
                ? View.VISIBLE : View.GONE);
        cleaning.setOnClickListener(view -> confirmOperation(R.string.complete_cleaning_question,
                () -> api.completeCleaning(selectedRoomId)));
        Button maintenance = findViewById(R.id.finishMaintenanceButton);
        maintenance.setVisibility(active == 0 && canAdmin() && state.equals("MANTENIMIENTO")
                && room.isNull("limpiezaPendienteDesde") ? View.VISIBLE : View.GONE);
        maintenance.setOnClickListener(view -> confirmOperation(R.string.finish_maintenance_question,
                () -> api.finishMaintenance(room)));
        for (int i = 0; i < movements.length(); i++) {
            JSONObject move = movements.optJSONObject(i);
            if (move == null) continue;
            View item = getLayoutInflater().inflate(R.layout.item_history, historyList, false);
            String description = move.optString("tipo", "") + " · "
                    + move.optString("momento", "").replace('T', ' ');
            String detail = move.optString("detalle", "");
            if (!detail.isEmpty() && !detail.equals("null")) description += "\n" + detail;
            ((TextView) item.findViewById(R.id.historyText)).setText(description);
            historyList.addView(item);
        }
        if (movements.length() == 0) addInfo(historyList, R.string.no_history);
        showStatus(findViewById(R.id.detailMessage), R.string.empty, false);
    }

    private void addInfo(LinearLayout container, int message) {
        View item = getLayoutInflater().inflate(R.layout.item_history, container, false);
        ((TextView) item.findViewById(R.id.historyText)).setText(message);
        container.addView(item);
    }

    private interface RoomOperation { void run() throws Exception; }

    private void confirmOperation(int question, RoomOperation operation) {
        if (operationBusy) return;
        new AlertDialog.Builder(this).setMessage(question)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> runOperation(operation))
                .show();
    }

    private void runOperation(RoomOperation operation) {
        if (operationBusy) return;
        operationBusy = true;
        int current = generation;
        showStatus(findViewById(R.id.detailMessage), R.string.operation_running, false);
        findViewById(R.id.completeCleaningButton).setEnabled(false);
        findViewById(R.id.finishMaintenanceButton).setEnabled(false);
        LinearLayout activeList = findViewById(R.id.activeStaysList);
        for (int i = 0; i < activeList.getChildCount(); i++) {
            Button button = activeList.getChildAt(i).findViewById(R.id.confirmDepartureButton);
            if (button != null) button.setEnabled(false);
        }
        worker.execute(() -> {
            try {
                operation.run();
                runOnUiThread(() -> {
                    if (current != generation) return;
                    roomRefreshRequired = true;
                    loadRoomDetail(); // Only the backend's new response can make a room available.
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    operationBusy = false;
                    if (failure instanceof AmautaApi.ApiException
                            && ((AmautaApi.ApiException) failure).status == 401) {
                        api.clear();
                        showLogin(getString(R.string.session_expired));
                    } else {
                        showStatus(findViewById(R.id.detailMessage), operationError(failure), true);
                        findViewById(R.id.retryDetailButton).setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private String operationError(Exception failure) {
        if (failure instanceof AmautaApi.ApiException) {
            AmautaApi.ApiException apiError = (AmautaApi.ApiException) failure;
            if (apiError.status == 403) return getString(R.string.operation_denied);
            if (apiError.status == 409) return getString(R.string.operation_conflict);
            if (apiError.getMessage() != null && !apiError.getMessage().isEmpty())
                return apiError.getMessage();
        }
        return getString(errorMessage(failure));
    }

    private String value(JSONObject room, String key) {
        String value = room.optString(key, "").trim();
        return value.isEmpty() || value.equals("null") ? getString(R.string.not_available) : value;
    }

    private void logout() {
        if (busy) return;
        busy = true;
        Button button = findViewById(screen == HOME ? R.id.homeLogout : R.id.logoutButton);
        button.setEnabled(false);
        int current = generation;
        worker.execute(() -> {
            String error = null;
            try { api.logout(); }
            catch (Exception failure) { error = getString(R.string.logout_connection_error); }
            String finalError = error;
            runOnUiThread(() -> {
                if (current == generation) {
                    lastRooms = null;
                    showLogin(finalError);
                }
            });
        });
    }

    @Override public void onBackPressed() {
        if (operationBusy || savingGuest) return;
        if (screen == ROOM_DETAIL) showRooms();
        else if (screen == GUEST_FORM || screen == GUEST_DETAIL) showGuests(false);
        else if (screen == RESERVATION_FORM || screen == RESERVATION_DETAIL) showReservations();
        else if (screen == ROOMS || screen == GUESTS || screen == RESERVATIONS) showHome();
        else super.onBackPressed();
    }

    private int errorMessage(Exception error) {
        if (error instanceof IOException) return R.string.connection_error;
        if (error instanceof AmautaApi.ApiException) {
            int status = ((AmautaApi.ApiException) error).status;
            if (status == 401) return R.string.session_expired;
            if (status == 403) return R.string.operation_denied;
        }
        return R.string.server_error;
    }

    @Override protected void onDestroy() {
        generation++;
        worker.shutdown();
        super.onDestroy();
    }
}
