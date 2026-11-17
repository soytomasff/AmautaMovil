package com.example.amautamovil;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reception screens share the authenticated, CSRF aware HTTP client with MainActivity. */
public class ReceptionActivity extends AppCompatActivity {
    private static final int LIST = 0, HISTORY = 1, FORM = 2, DETAIL = 3;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AmautaApi api = AmautaApi.get();
    private final android.os.Handler alertHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable alertRefresh = new Runnable() {
        @Override public void run() {
            if (mode == LIST && !busy) loadList();
            alertHandler.postDelayed(this, 30000);
        }
    };
    private int mode = LIST, generation;
    private boolean busy, changed;
    private long detailId = -1, requestedReservationId = -1;
    private JSONArray stays, reservations, guests, rooms;
    private String query = "", stateFilter = "", entryDate = "", entryTime = "";
    private String plannedDate = "", plannedTime = "11:00";
    private Spinner guestChoice, roomChoice, reservationChoice;
    private final List<Long> guestIds = new ArrayList<>();
    private final List<Long> roomIds = new ArrayList<>();
    private final List<Long> reservationIds = new ArrayList<>();
    private Button submit;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestedReservationId = getIntent().getLongExtra("reservationId", -1);
        if (requestedReservationId > 0) showForm(); else showList(false);
    }

    @Override protected void onResume() {
        super.onResume();
        alertHandler.postDelayed(alertRefresh, 30000);
    }

    @Override protected void onPause() {
        alertHandler.removeCallbacks(alertRefresh);
        super.onPause();
    }

    private void base(int next, String title) {
        generation++;
        mode = next;
        setContentView(R.layout.activity_reception);
        ((TextView) findViewById(R.id.receptionTitle)).setText(title);
        View root = ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        int left = root.getPaddingLeft(), top = root.getPaddingTop();
        int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(left + bars.left, top + bars.top,
                    right + bars.right, bottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        if (getResources().getConfiguration().smallestScreenWidthDp >= 600) {
            View column = findViewById(R.id.contentColumn);
            android.view.ViewGroup.LayoutParams params = column.getLayoutParams();
            params.width = dp(Math.min(760, getResources().getConfiguration().screenWidthDp - 32));
            if (params instanceof android.widget.FrameLayout.LayoutParams)
                ((android.widget.FrameLayout.LayoutParams) params).gravity = android.view.Gravity.CENTER_HORIZONTAL;
            column.setLayoutParams(params);
        }
        findViewById(R.id.navReservations).setSelected(true);
        findViewById(R.id.navHome).setOnClickListener(v -> returnTo("home"));
        findViewById(R.id.navRooms).setOnClickListener(v -> returnTo("rooms"));
        findViewById(R.id.navGuests).setOnClickListener(v -> returnTo("guests"));
        findViewById(R.id.navReservations).setOnClickListener(v -> {
            if (busy) return;
            new AlertDialog.Builder(this).setItems(new String[] {"Reservas", "Recepción",
                    getString(R.string.cleaning_title)},
                    (dialog, which) -> { if (which == 0) returnTo("reservations");
                        else if (which == 1) showList(false);
                        else returnTo("cleaning"); }).show();
        });
    }

    private void returnTo(String destination) {
        if (busy) return;
        setResult(RESULT_OK, new Intent().putExtra("destination", destination)
                .putExtra("refreshRooms", changed));
        finish();
    }

    private LinearLayout actions() { return findViewById(R.id.receptionActions); }
    private LinearLayout content() { return findViewById(R.id.receptionContent); }
    private TextView message() { return findViewById(R.id.receptionMessage); }

    private Button button(LinearLayout parent, String label, Runnable action, boolean outlined) {
        Button button = new Button(this);
        button.setText(label);
        if (outlined) button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.brand_raised)));
        else if (label.startsWith("+") || label.equals("Confirmar check-in")) {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.gold)));
            button.setTextColor(ContextCompat.getColor(this, R.color.brand_dark));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.topMargin = dp(8);
        parent.addView(button, params);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView text(LinearLayout parent, String value, int size, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(ContextCompat.getColor(this, R.color.ink));
        if (bold) label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setPadding(0, dp(7), 0, dp(7));
        parent.addView(label);
        return label;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        parent.addView(card, params);
        return card;
    }

    private void status(String value, boolean error) {
        TextView view = message();
        view.setText(value);
        view.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        view.setTextColor(ContextCompat.getColor(this, error ? R.color.error : R.color.muted));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(this,
                error ? R.color.error_surface : R.color.info_surface));
        background.setCornerRadius(dp(12));
        view.setBackground(background);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
    }

    private void showList(boolean history) {
        if (busy) return;
        base(history ? HISTORY : LIST, history ? "Historial de estadías" : "Recepción");
        button(actions(), history ? "Volver a Recepción" : "+ Nuevo check-in",
                history ? () -> showList(false) : this::showForm, false);
        if (!history) button(actions(), "Historial de estadías", () -> showList(true), true);
        button(actions(), "Actualizar", () -> loadList(), true);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Buscar huésped, documento o habitación");
        search.setText(query);
        actions().addView(search, new LinearLayout.LayoutParams(-1, dp(56)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString(); renderList();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        loadList();
    }

    private void loadList() {
        if (busy) return;
        status("Cargando datos de Recepción…", false);
        content().removeAllViews();
        int current = ++generation;
        worker.execute(() -> {
            try {
                JSONArray loadedStays = api.stays();
                JSONArray loadedReservations = mode == LIST ? api.reservations() : new JSONArray();
                JSONArray loadedRooms = mode == LIST ? api.rooms() : new JSONArray();
                runOnUiThread(() -> {
                    if (current != generation) return;
                    stays = loadedStays;
                    reservations = loadedReservations;
                    rooms = loadedRooms;
                    setupStateFilter();
                    renderList();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    if (expired(failure)) return;
                    status(error(failure), true);
                    button(content(), "Reintentar", this::loadList, false);
                });
            }
        });
    }

    private void setupStateFilter() {
        View oldFilter = actions().findViewWithTag("stateFilter");
        if (oldFilter != null) actions().removeView(oldFilter);
        Set<String> states = new LinkedHashSet<>();
        for (int i = 0; i < stays.length(); i++) {
            JSONObject stay = stays.optJSONObject(i);
            if (stay != null && active(stay) == (mode == LIST)) {
                String state = stay.optString("estado", "").trim();
                if (!state.isEmpty()) states.add(state);
            }
        }
        if (mode == LIST && reservations != null) for (int i = 0; i < reservations.length(); i++) {
            JSONObject reservation = reservations.optJSONObject(i);
            if (reservation != null && reservable(reservation)) {
                String state = reservation.optString("estado", "").trim();
                if (!state.isEmpty()) states.add(state);
            }
        }
        List<String> choices = new ArrayList<>();
        choices.add("Todos los estados"); choices.addAll(states);
        Spinner filter = new Spinner(this);
        filter.setTag("stateFilter");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filter.setAdapter(adapter);
        actions().addView(filter, new LinearLayout.LayoutParams(-1, dp(52)));
        filter.setSelection(Math.max(0, choices.indexOf(stateFilter)));
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                stateFilter = position == 0 ? "" : choices.get(position);
                renderList();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void renderList() {
        if (stays == null || (mode != LIST && mode != HISTORY)) return;
        LinearLayout list = content();
        list.removeAllViews();
        int shown = 0;
        if (mode == LIST) {
            showDepartureAlerts(list);
            text(list, "Huéspedes alojados", 21, true);
            shown += renderStays(list, true);
            text(list, "Entradas previstas", 21, true);
            int arrivals = 0;
            if (reservations != null) for (int i = 0; i < reservations.length(); i++) {
                JSONObject reservation = reservations.optJSONObject(i);
                if (reservation == null || !reservable(reservation)
                        || (!stateFilter.isEmpty()
                        && !stateFilter.equals(reservation.optString("estado")))) continue;
                JSONObject guest = reservation.optJSONObject("huesped");
                JSONObject room = reservation.optJSONObject("habitacion");
                if (guest != null && hasActiveGuest(guest.optLong("id", -1))) continue;
                if (!matches(name(guest), guest == null ? "" : guest.optString("dni"),
                        room == null ? "" : room.optString("numero"))) continue;
                arrivals++;
                LinearLayout item = card(list);
                text(item, name(guest) + " · Hab. " + (room == null ? "—" : room.optString("numero")), 17, true);
                text(item, reservation.optString("fechaIngreso") + " → "
                        + reservation.optString("fechaSalida") + " · "
                        + reservation.optString("estado"), 14, false);
                long id = reservation.optLong("id", -1);
                if (id > 0) button(item, "Preparar check-in", () -> {
                    requestedReservationId = id; showForm();
                }, true);
            }
            if (arrivals == 0) text(list, "No hay entradas previstas para esta búsqueda.", 15, false);
            text(list, "Salidas previstas", 21, true);
            int exits = 0;
            for (int i = 0; i < stays.length(); i++) {
                JSONObject stay = stays.optJSONObject(i);
                if (stay == null || !active(stay) || stay.isNull("salidaPrevista")
                        || !matchStay(stay)) continue;
                exits++;
                LinearLayout item = card(list);
                text(item, stay.optString("nombreHuesped") + " · Hab. "
                        + stay.optString("numeroCuarto"), 16, true);
                text(item, "Salida prevista: " + stay.optString("salidaPrevista"), 14, false);
                long id = stay.optLong("id", -1);
                if (id > 0) button(item, "Ver detalle", () -> showDetail(id), true);
            }
            if (exits == 0) text(list, "No hay salidas previstas para esta búsqueda.", 15, false);
            text(list, "Habitaciones ocupadas", 21, true);
            int occupied = 0;
            if (rooms != null) for (int i = 0; i < rooms.length(); i++) {
                JSONObject room = rooms.optJSONObject(i);
                if (room == null || RoomRules.category(room.optString("estado"),
                        room.optString("estadoOperativo"),
                        !room.isNull("limpiezaPendienteDesde")) != RoomRules.OCCUPIED
                        || !matches("", "", room.optString("numero"))) continue;
                occupied++;
                LinearLayout item = card(list);
                text(item, "Habitación " + room.optString("numero", "—"), 17, true);
                text(item, room.optString("estadoOperativo", room.optString("estado", "—")), 14, false);
                button(item, "Ver habitaciones", () -> returnTo("rooms"), true);
            }
            if (occupied == 0) text(list, "No hay habitaciones ocupadas para esta búsqueda.", 15, false);
            shown += arrivals + exits + occupied;
        } else {
            text(list, "Estadías finalizadas", 21, true);
            shown = renderStays(list, false);
        }
        status(shown == 0 ? "No hay estadías para esta búsqueda." : "", false);
    }

    private void showDepartureAlerts(LinearLayout list) {
        long now = System.currentTimeMillis();
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("America/Lima"));
        for (int i = 0; i < stays.length(); i++) {
            JSONObject stay = stays.optJSONObject(i);
            if (stay == null || !active(stay)) continue;
            String planned = stay.optString("salidaPrevista", "");
            if (planned.length() < 16) continue;
            try {
                long until = format.parse(planned.substring(0, 16)).getTime() - now;
                if (until > 60 * 60 * 1000L) continue;
                LinearLayout alert = card(list);
                text(alert, until <= 0 ? "Salida vencida" : "Salida próxima (menos de 1 hora)", 17, true);
                text(alert, stay.optString("nombreHuesped", "Huésped") + " · Hab. "
                        + stay.optString("numeroCuarto", "—") + " · "
                        + planned.replace('T', ' '), 15, false);
                long id = stay.optLong("id", -1);
                if (id > 0) button(alert, "Revisar salida", () -> showDetail(id), true);
            } catch (java.text.ParseException ignored) { /* Ignore invalid legacy dates. */ }
        }
    }

    private int renderStays(LinearLayout list, boolean activeOnly) {
        int count = 0;
        for (int i = 0; i < stays.length(); i++) {
            JSONObject stay = stays.optJSONObject(i);
            if (stay == null || active(stay) != activeOnly || !matchStay(stay)) continue;
            count++;
            LinearLayout item = card(list);
            text(item, stay.optString("nombreHuesped", "Huésped") + " · Hab. "
                    + stay.optString("numeroCuarto", "—"), 18, true);
            text(item, "Ingreso: " + stay.optString("fechaIngreso", "—") + " "
                    + stay.optString("horaIngreso", "") + "\nEstado: "
                    + stay.optString("estado", "—") + (activeOnly ? "\nSalida prevista: "
                    + stay.optString("salidaPrevista", "—") : "\nSalida: "
                    + stay.optString("fechaSalida", "—") + " "
                    + stay.optString("horaSalida", "")), 15, false);
            long id = stay.optLong("id", -1);
            if (id > 0) button(item, "Ver detalle", () -> showDetail(id), true);
            if (activeOnly && id > 0) button(item, "Realizar check-out",
                    () -> showDetail(id), false);
        }
        if (count == 0) text(list, activeOnly ? "No hay huéspedes alojados para esta búsqueda."
                : "No hay estadías en el historial para esta búsqueda.", 15, false);
        return count;
    }

    private boolean matchStay(JSONObject stay) {
        return (stateFilter.isEmpty() || stateFilter.equals(stay.optString("estado")))
                && matches(stay.optString("nombreHuesped"), stay.optString("dni"),
                stay.optString("numeroCuarto"));
    }
    private boolean matches(String guest, String document, String room) {
        String term = query.trim().toLowerCase(Locale.ROOT);
        return term.isEmpty() || (guest + " " + document + " " + room)
                .toLowerCase(Locale.ROOT).contains(term);
    }
    private boolean active(JSONObject stay) {
        return stay.isNull("fechaSalida") || stay.isNull("horaSalida");
    }
    private boolean hasActiveGuest(long guestId) {
        if (stays == null || guestId <= 0) return false;
        for (int i = 0; i < stays.length(); i++) {
            JSONObject stay = stays.optJSONObject(i);
            if (stay != null && active(stay) && stay.optLong("huespedId", -1) == guestId)
                return true;
        }
        return false;
    }
    private boolean reservable(JSONObject reservation) {
        String state = reservation.optString("estado", "").toUpperCase(Locale.ROOT);
        return state.equals("RESERVADO") || state.equals("CONFIRMADO");
    }
    private String name(JSONObject guest) {
        if (guest == null) return "Huésped";
        return (guest.optString("nombre") + " " + guest.optString("apellido")).trim();
    }

    private void showForm() {
        if (busy) return;
        base(FORM, "Nuevo check-in");
        button(actions(), "Volver a Recepción", () -> showList(false), true);
        status("Cargando huéspedes, habitaciones y reservas…", false);
        int current = generation;
        worker.execute(() -> {
            try {
                JSONArray loadedGuests = api.guests();
                JSONArray loadedRooms = api.rooms();
                JSONArray loadedReservations = api.reservations();
                JSONArray loadedStays = api.stays();
                JSONObject configuration = api.checkoutConfiguration();
                runOnUiThread(() -> {
                    if (current != generation) return;
                    guests = loadedGuests; rooms = loadedRooms;
                    reservations = loadedReservations; stays = loadedStays;
                    plannedTime = configuration.optString("horaSalida", "11:00");
                    if (plannedTime.length() > 5) plannedTime = plannedTime.substring(0, 5);
                    renderForm();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    if (expired(failure)) return;
                    status(error(failure), true);
                    button(content(), "Reintentar", this::showForm, false);
                });
            }
        });
    }

    private Spinner spinner(LinearLayout parent, String label, List<String> options) {
        text(parent, label, 16, true);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, dp(52)));
        return spinner;
    }

    private void renderForm() {
        status("La disponibilidad final se confirma al guardar en Spring Boot.", false);
        LinearLayout form = content();
        form.removeAllViews();
        guestIds.clear(); roomIds.clear(); reservationIds.clear();
        List<String> guestNames = new ArrayList<>(); guestNames.add("Seleccionar huésped");
        guestIds.add(-1L);
        for (int i = 0; i < guests.length(); i++) {
            JSONObject guest = guests.optJSONObject(i);
            if (guest == null) continue;
            guestIds.add(guest.optLong("id", -1));
            guestNames.add(name(guest) + " · " + guest.optString("dni"));
        }
        guestChoice = spinner(form, "Huésped registrado", guestNames);
        List<String> roomNames = new ArrayList<>(); roomNames.add("Seleccionar habitación");
        roomIds.add(-1L);
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject room = rooms.optJSONObject(i);
            if (room == null || !roomAvailable(room)) continue;
            roomIds.add(room.optLong("id", -1));
            roomNames.add("Hab. " + room.optString("numero", room.optString("codigo"))
                    + " · " + room.optString("estadoOperativo", room.optString("estado")));
        }
        roomChoice = spinner(form, "Habitación disponible ahora", roomNames);
        List<String> reservationNames = new ArrayList<>();
        reservationNames.add("Ingreso directo (sin reserva)"); reservationIds.add(-1L);
        String today = peruDate();
        for (int i = 0; i < reservations.length(); i++) {
            JSONObject reservation = reservations.optJSONObject(i);
            if (reservation == null || !reservable(reservation)
                    || today.compareTo(reservation.optString("fechaIngreso")) < 0
                    || today.compareTo(reservation.optString("fechaSalida")) >= 0) continue;
            JSONObject reservedGuest = reservation.optJSONObject("huesped");
            if (reservedGuest != null && hasActiveGuest(reservedGuest.optLong("id", -1))) continue;
            reservationIds.add(reservation.optLong("id", -1));
            reservationNames.add("Reserva #" + reservation.optLong("id") + " · "
                    + name(reservation.optJSONObject("huesped")));
        }
        reservationChoice = spinner(form, "Reserva", reservationNames);
        reservationChoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                long reservationId = reservationIds.get(position);
                JSONObject reservation = find(reservations, reservationId);
                guestChoice.setEnabled(reservation == null);
                roomChoice.setEnabled(reservation == null);
                if (reservation == null) return;
                plannedDate = reservation.optString("fechaSalida", plannedDate);
                Button planned = plannedDateButton();
                if (planned != null) planned.setText("Fecha prevista: " + plannedDate);
                JSONObject guest = reservation.optJSONObject("huesped");
                JSONObject room = reservation.optJSONObject("habitacion");
                guestChoice.setSelection(Math.max(0, guestIds.indexOf(
                        guest == null ? -1L : guest.optLong("id", -1))));
                int roomIndex = roomIds.indexOf(room == null ? -1L : room.optLong("id", -1));
                roomChoice.setSelection(Math.max(0, roomIndex));
                if (roomIndex < 1) status("La habitación reservada no está disponible ahora.", true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        if (requestedReservationId > 0) {
            int selected = reservationIds.indexOf(requestedReservationId);
            if (selected > 0) reservationChoice.setSelection(selected);
            else status("Esta reserva no admite check-in en la fecha actual.", true);
            requestedReservationId = -1;
        }
        Calendar now = peruNow();
        entryDate = String.format(Locale.ROOT, "%04d-%02d-%02d",
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
        entryTime = String.format(Locale.ROOT, "%02d:%02d",
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        Calendar tomorrow = (Calendar) now.clone();
        tomorrow.add(Calendar.DAY_OF_MONTH, 1);
        plannedDate = String.format(Locale.ROOT, "%04d-%02d-%02d",
                tomorrow.get(Calendar.YEAR), tomorrow.get(Calendar.MONTH) + 1,
                tomorrow.get(Calendar.DAY_OF_MONTH));
        Button date = button(form, "Fecha de ingreso: " + entryDate, () -> {
            DatePickerDialog picker = new DatePickerDialog(this, (view, year, month, day) -> {
                entryDate = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day);
                dateButton().setText("Fecha de ingreso: " + entryDate);
            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
            picker.getDatePicker().setMaxDate(peruNow().getTimeInMillis());
            picker.show();
        }, true);
        date.setTag("date");
        Button time = button(form, "Hora de ingreso: " + entryTime, () -> {
            Calendar selected = peruNow();
            new TimePickerDialog(this, (view, hour, minute) -> {
                entryTime = String.format(Locale.ROOT, "%02d:%02d", hour, minute);
                timeButton().setText("Hora de ingreso: " + entryTime);
            }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), true).show();
        }, true);
        time.setTag("time");
        plannedControls(form);
        submit = button(form, "Confirmar check-in", this::submitCheckIn, false);
        submit.setEnabled(guestIds.size() > 1 && roomIds.size() > 1);
        if (guestIds.size() <= 1) status("No hay huéspedes registrados para el check-in.", false);
        else if (roomIds.size() <= 1) status("No hay habitaciones disponibles ahora.", false);
    }

    private Button dateButton() { return content().findViewWithTag("date"); }
    private Button timeButton() { return content().findViewWithTag("time"); }
    private Button plannedDateButton() { return content().findViewWithTag("plannedDate"); }
    private Button plannedTimeButton() { return content().findViewWithTag("plannedTime"); }

    private void plannedControls(LinearLayout parent) {
        text(parent, "Salida prevista (hora de Perú)", 16, true);
        Calendar date = peruNow();
        try {
            String[] parts = plannedDate.split("-");
            date.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1,
                    Integer.parseInt(parts[2]));
        } catch (Exception ignored) { /* The picker starts on today if the server has no date. */ }
        Button day = button(parent, "Fecha prevista: " + plannedDate, () ->
                new DatePickerDialog(this, (view, year, month, selected) -> {
                    plannedDate = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, selected);
                    plannedDateButton().setText("Fecha prevista: " + plannedDate);
                }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show(), true);
        day.setTag("plannedDate");
        Button hour = button(parent, "Hora prevista: " + plannedTime, () -> {
            String[] parts = plannedTime.split(":");
            new TimePickerDialog(this, (view, selectedHour, selectedMinute) -> {
                plannedTime = String.format(Locale.ROOT, "%02d:%02d", selectedHour, selectedMinute);
                plannedTimeButton().setText("Hora prevista: " + plannedTime);
            }, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), true).show();
        }, true);
        hour.setTag("plannedTime");
    }
    private JSONObject find(JSONArray items, long id) {
        if (items == null || id <= 0) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && item.optLong("id", -1) == id) return item;
        }
        return null;
    }
    private boolean roomAvailable(JSONObject room) {
        int category = RoomRules.category(room.optString("estado"), room.optString("estadoOperativo"),
                !room.isNull("limpiezaPendienteDesde"));
        return room.isNull("limpiezaPendienteDesde") && (category == RoomRules.FREE
                || (category == RoomRules.OCCUPIED && room.optLong("camasDisponibles", 0) > 0));
    }

    private void submitCheckIn() {
        if (busy || guestChoice == null || roomChoice == null) return;
        long guestId = guestIds.get(guestChoice.getSelectedItemPosition());
        long roomId = roomIds.get(roomChoice.getSelectedItemPosition());
        long reservationId = reservationIds.get(reservationChoice.getSelectedItemPosition());
        if (guestId <= 0 || roomId <= 0) { status("Selecciona huésped y habitación.", true); return; }
        String nowDate = peruDate();
        String nowTime = String.format(Locale.ROOT, "%02d:%02d",
                peruNow().get(Calendar.HOUR_OF_DAY), peruNow().get(Calendar.MINUTE));
        if (entryDate.compareTo(nowDate) > 0 || (entryDate.equals(nowDate)
                && entryTime.compareTo(nowTime) > 0)) {
            status("La fecha y hora de ingreso no pueden ser futuras.", true); return;
        }
        if ((plannedDate + "T" + plannedTime).compareTo(entryDate + "T" + entryTime) < 0) {
            status("La salida prevista no puede ser anterior al ingreso.", true); return;
        }
        if (reservationId > 0) {
            JSONObject reservation = find(reservations, reservationId);
            JSONObject reservedRoom = reservation == null ? null : reservation.optJSONObject("habitacion");
            JSONObject reservedGuest = reservation == null ? null : reservation.optJSONObject("huesped");
            if (reservation == null || !reservable(reservation)
                    || reservedRoom == null || reservedGuest == null
                    || reservedRoom.optLong("id") != roomId || reservedGuest.optLong("id") != guestId
                    || entryDate.compareTo(reservation.optString("fechaIngreso")) < 0
                    || entryDate.compareTo(reservation.optString("fechaSalida")) >= 0) {
                status("La reserva no coincide con el huésped, la habitación o la fecha.", true); return;
            }
        }
        if (stays != null) for (int i = 0; i < stays.length(); i++) {
            JSONObject stay = stays.optJSONObject(i);
            if (stay != null && active(stay) && stay.optLong("huespedId", -1) == guestId) {
                status("Este huésped ya tiene una estadía activa.", true); return;
            }
        }
        busy = true; submit.setEnabled(false);
        status("Registrando ingreso…", false);
        int current = generation;
        worker.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("huespedId", guestId); body.put("habitacionId", roomId);
                body.put("fechaIngreso", entryDate); body.put("horaIngreso", entryTime);
                body.put("salidaPrevista", plannedDate + "T" + plannedTime + ":00");
                if (reservationId > 0) body.put("reservaId", reservationId);
                JSONObject saved = api.checkIn(body);
                long id = saved.optLong("id", -1);
                if (id <= 0) throw new IOException("El servidor no confirmó el registro");
                runOnUiThread(() -> {
                    if (current != generation) return;
                    busy = false; changed = true; detailId = id;
                    showDetail(id);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    busy = false; submit.setEnabled(true);
                    status(error(failure), true);
                });
            }
        });
    }

    private void showDetail(long id) {
        if (busy || id <= 0) return;
        detailId = id;
        base(DETAIL, "Detalle de estadía #" + id);
        button(actions(), "Volver a Recepción", () -> showList(false), true);
        button(actions(), "Actualizar", () -> loadDetail(), true);
        loadDetail();
    }

    private void loadDetail() {
        if (busy) return;
        status("Consultando estadía…", false);
        content().removeAllViews();
        int current = ++generation;
        worker.execute(() -> {
            try {
                JSONArray loaded = api.stays();
                JSONObject stay = find(loaded, detailId);
                if (stay == null) throw new AmautaApi.ApiException(404, "Estadía no encontrada");
                JSONArray movements = api.stayMovements(detailId);
                runOnUiThread(() -> {
                    if (current != generation) return;
                    stays = loaded;
                    renderDetail(stay, movements);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    if (expired(failure)) return;
                    status(error(failure), true);
                    button(content(), "Reintentar", this::loadDetail, false);
                });
            }
        });
    }

    private void renderDetail(JSONObject stay, JSONArray movements) {
        status("", false);
        LinearLayout item = card(content());
        text(item, stay.optString("nombreHuesped", "Huésped"), 21, true);
        text(item, "Documento: " + stay.optString("dni", "—")
                + "\nHabitación: " + stay.optString("numeroCuarto", "—")
                + "\nIngreso: " + stay.optString("fechaIngreso", "—") + " "
                + stay.optString("horaIngreso", "")
                + "\nSalida prevista: " + stay.optString("salidaPrevista", "—")
                + "\nEstado: " + stay.optString("estado", "—"), 16, false);
        if (!stay.isNull("fechaSalida") && !stay.isNull("horaSalida"))
            text(item, "Salida: " + stay.optString("fechaSalida") + " "
                    + stay.optString("horaSalida"), 16, false);
        if (!stay.isNull("precio"))
            text(item, "Precio registrado: " + stay.optString("precio"), 16, false);
        if (active(stay)) {
            String planned = stay.optString("salidaPrevista", "");
            if (planned.length() >= 16) {
                plannedDate = planned.substring(0, 10);
                plannedTime = planned.substring(11, 16);
            } else {
                plannedDate = peruDate();
                plannedTime = "11:00";
            }
            plannedControls(item);
            button(item, "Guardar salida prevista", () -> savePlanned(stay), false);
        }
        if (active(stay)) button(item, "Realizar check-out", () -> new AlertDialog.Builder(this)
                .setTitle("Confirmar check-out")
                .setMessage("Se registrará la salida de " + stay.optString("nombreHuesped")
                        + " de la habitación " + stay.optString("numeroCuarto")
                        + ". La habitación quedará sujeta a limpieza según el servidor.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Confirmar", (dialog, which) -> checkout(stay.optLong("id")))
                .show(), false);
        text(content(), "Movimientos de la estadía", 20, true);
        if (movements.length() == 0) text(content(), "No hay movimientos registrados.", 15, false);
        for (int i = 0; i < movements.length(); i++) {
            JSONObject movement = movements.optJSONObject(i);
            if (movement == null) continue;
            LinearLayout movementCard = card(content());
            text(movementCard, movement.optString("tipo", "Movimiento") + " · "
                    + movement.optString("fecha", movement.optString("fechaHora", "")), 15, true);
            String note = movement.optString("detalle", movement.optString("descripcion", ""));
            if (!note.isEmpty()) text(movementCard, note, 14, false);
        }
    }

    private void checkout(long id) {
        if (busy || id <= 0) return;
        busy = true;
        status("Confirmando salida…", false);
        int current = generation;
        worker.execute(() -> {
            try {
                api.confirmDeparture(id);
                runOnUiThread(() -> {
                    if (current != generation) return;
                    busy = false; changed = true;
                    loadDetail();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    busy = false;
                    if (!expired(failure)) status(error(failure), true);
                });
            }
        });
    }

    private void savePlanned(JSONObject stay) {
        if (busy) return;
        String value = plannedDate + "T" + plannedTime + ":00";
        String ingress = stay.optString("fechaIngreso", "") + "T"
                + stay.optString("horaIngreso", "");
        if (value.compareTo(ingress) < 0) {
            status("La salida prevista no puede ser anterior al ingreso.", true);
            return;
        }
        busy = true;
        status("Guardando salida prevista…", false);
        int current = generation;
        worker.execute(() -> {
            try {
                api.programDeparture(stay.optLong("id"), value);
                runOnUiThread(() -> {
                    if (current != generation) return;
                    busy = false;
                    changed = true;
                    loadDetail();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (current != generation) return;
                    busy = false;
                    if (!expired(failure)) status(error(failure), true);
                });
            }
        });
    }

    private boolean expired(Exception failure) {
        if (failure instanceof AmautaApi.ApiException
                && ((AmautaApi.ApiException) failure).status == 401) {
            api.clear();
            new AlertDialog.Builder(this).setMessage(R.string.session_expired)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        setResult(RESULT_OK, new Intent().putExtra("destination", "login"));
                        finish();
                    }).setCancelable(false).show();
            return true;
        }
        return false;
    }

    private String error(Exception failure) {
        if (failure instanceof IOException) return getString(R.string.connection_error);
        if (failure instanceof AmautaApi.ApiException) {
            AmautaApi.ApiException response = (AmautaApi.ApiException) failure;
            if (response.status == 403) return getString(R.string.operation_denied);
            if (response.status == 404) return mode == FORM
                    ? "Operación de Recepción no disponible. Reinicia Spring Boot con el módulo nuevo."
                    : "No se encontró la estadía solicitada.";
            if (response.status == 409) return response.getMessage() == null
                    || response.getMessage().isEmpty() ? getString(R.string.operation_conflict)
                    : response.getMessage();
            if (response.status == 400) return response.getMessage() == null
                    || response.getMessage().isEmpty() ? "Datos de ingreso no válidos." : response.getMessage();
        }
        return getString(R.string.server_error);
    }

    private Calendar peruNow() { return Calendar.getInstance(TimeZone.getTimeZone("America/Lima")); }
    private String peruDate() {
        Calendar now = peruNow();
        return String.format(Locale.ROOT, "%04d-%02d-%02d", now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (busy) return;
        if (mode == DETAIL || mode == HISTORY || mode == FORM) showList(false);
        else { setResult(RESULT_OK, new Intent().putExtra("refreshRooms", changed)); finish(); }
    }

    @Override protected void onDestroy() {
        generation++;
        worker.shutdown();
        super.onDestroy();
    }
}
